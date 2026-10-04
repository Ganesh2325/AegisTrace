"""Background worker. Postgres is the queue. Tickets are idempotent."""

from __future__ import annotations

import json
import os
import socket
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import boto3
import httpx
import psycopg
from botocore.client import Config
from psycopg.rows import dict_row

from aegislib.chunking import chunk_markdown
from aegislib.embedding import MODEL_ID, embed, to_pgvector
from aegislib.execution import ExecutionError, execute_ticket
from aegislib.retries import backoff_seconds, classify_retry

DATABASE_URL = os.environ["DATABASE_URL"]
TOKEN = os.environ.get("AEGIS_INTERNAL_TOKEN", "")
CONTROL_PLANE = os.environ.get("CONTROL_PLANE_URL", "http://control-plane:8080")
SEED_DIR = Path(os.environ.get("SEED_DIR", "/seed"))
WORKER = f"{socket.gethostname()}-{os.getpid()}"


def main() -> None:
    threading.Thread(target=_health, daemon=True).start()
    wait_for_db()
    if os.environ.get("SEED_KNOWLEDGE", "true").lower() == "true":
        try:
            seed_knowledge()
        except Exception as exc:
            print(f"seed_failed {exc.__class__.__name__}")
    while True:
        job = claim()
        if job is None:
            time.sleep(0.5)
            continue
        try:
            process(job)
            finish(job["id"], "SUCCEEDED", None)
        except ExecutionError as exc:
            fail(job, exc.error_type, str(exc), exc.retryable)
        except Exception as exc:
            retryable = classify_retry(exc.__class__.__name__)
            fail(job, exc.__class__.__name__, str(exc)[:500], retryable)


def _health() -> None:
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            ok = self.path in {"/liveness", "/readiness", "/health"}
            self.send_response(200 if ok else 404)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"status":"UP","service":"worker"}' if ok else b"{}")

        def log_message(self, fmt, *args):
            return

    ThreadingHTTPServer(("0.0.0.0", 8091), Handler).serve_forever()


def wait_for_db() -> None:
    for _ in range(60):
        try:
            with psycopg.connect(DATABASE_URL, connect_timeout=2) as conn:
                conn.execute("select 1")
            return
        except Exception:
            time.sleep(1)
    raise SystemExit("postgres did not become ready")


def connect():
    return psycopg.connect(DATABASE_URL, row_factory=dict_row)


def claim():
    with connect() as conn:
        row = conn.execute(
            """
            with next as (
                select id from jobs
                where status in ('PENDING', 'RETRY') and next_run_at <= now()
                order by next_run_at
                for update skip locked
                limit 1
            )
            update jobs j
            set status = 'RUNNING', locked_by = %s, locked_until = now() + interval '60 seconds',
                attempts = attempts + 1, updated_at = now()
            from next
            where j.id = next.id
            returning j.*
            """,
            (WORKER,),
        ).fetchone()
        conn.commit()
        return row


def finish(job_id, status: str, error: str | None) -> None:
    with connect() as conn:
        conn.execute(
            """
            update jobs set status = %s, locked_by = null, locked_until = null, last_error = %s, updated_at = now()
            where id = %s
            """,
            (status, error, job_id),
        )
        conn.commit()


def fail(job, error_type: str, message: str, retryable: bool) -> None:
    attempt = int(job["attempts"])
    with connect() as conn:
        conn.execute(
            """
            insert into job_attempts (id, job_id, attempt, error_type, error_message, retryable)
            values (%s, %s, %s, %s, %s, %s)
            """,
            (str(uuid.uuid4()), job["id"], attempt, error_type, message, retryable),
        )
        if retryable and attempt < int(job["max_attempts"]):
            conn.execute(
                """
                update jobs set status = 'RETRY', locked_by = null, locked_until = null,
                    next_run_at = now() + (%s || ' seconds')::interval, last_error = %s, updated_at = now()
                where id = %s
                """,
                (str(backoff_seconds(attempt)), f"{error_type}: {message}", job["id"]),
            )
        else:
            conn.execute(
                """
                update jobs set status = 'DEAD', locked_by = null, locked_until = null, last_error = %s, updated_at = now()
                where id = %s
                """,
                (f"{error_type}: {message}", job["id"]),
            )
        conn.commit()


def process(job) -> None:
    payload = job["payload"] if isinstance(job["payload"], dict) else json.loads(job["payload"])
    job["payload"] = payload
    if job["job_type"] == "EMBED_DOCUMENT":
        embed_document(payload["documentId"])
    elif job["job_type"] == "EXECUTE_TOOL":
        execute_and_callback(job)
    elif job["job_type"] == "EVALUATE_RUN":
        evaluate_run(payload["runId"])
    elif job["job_type"] == "SIMULATE":
        mode = payload.get("mode")
        if mode == "ticket_400":
            raise ExecutionError("TICKET_400", "Simulated invalid ticket.", False)
        if mode in {"ticket_500", "ticket_timeout", "crash_before_insert"}:
            raise ExecutionError("TICKET_500" if mode != "ticket_timeout" else "TIMEOUT", "Simulated failure.", True)
        if mode == "crash_after_insert":
            raise ExecutionError("TICKET_500", "Simulated crash after a no-op insert.", True)
        raise ExecutionError("INVALID_ARGUMENTS", "Unknown simulation.", False)
    else:
        raise ExecutionError("INVALID_ARGUMENTS", "Unknown job type.", False)


def execute_and_callback(job) -> None:
    payload = job["payload"]
    mode = payload.get("simulate")
    with psycopg.connect(DATABASE_URL) as conn:
        try:
            result = execute_ticket(conn, job, simulate=mode)
            conn.commit()
        except ExecutionError:
            conn.rollback()
            raise
    if mode == "crash_after_insert":
        raise ExecutionError("TICKET_500", "Simulated crash after insert.", True)
    status = "SUCCEEDED" if result.get("ticketId") or result.get("status") == "SKIPPED" else "FAILED"
    if result.get("status") == "SKIPPED":
        callback(payload["runId"], {
            "proposalId": payload["proposalId"],
            "status": "SKIPPED",
            "retryable": False,
            "result": result,
            "errorType": result.get("reason", "SKIPPED"),
            "errorMessage": "Execution skipped.",
        })
        return
    callback(payload["runId"], {
        "proposalId": payload["proposalId"],
        "status": "SUCCEEDED",
        "retryable": False,
        "result": result,
        "errorType": None,
        "errorMessage": None,
    })


def callback(run_id: str, body: dict) -> None:
    with httpx.Client(timeout=10) as client:
        response = client.post(
            f"{CONTROL_PLANE}/internal/runs/{run_id}/tool-result",
            headers={"X-Internal-Token": TOKEN},
            json=body,
        )
        response.raise_for_status()


def embed_document(document_id: str) -> None:
    with connect() as conn:
        doc = conn.execute("select * from documents where id = %s", (document_id,)).fetchone()
        if doc is None:
            raise ExecutionError("INVALID_ARGUMENTS", "Document does not exist.", False)
        if doc["status"] == "ACTIVE":
            return
        conn.execute("update documents set status = 'PROCESSING' where id = %s", (document_id,))
        kb = conn.execute("select embedding_model from knowledge_bases where id = %s", (doc["knowledge_base_id"],)).fetchone()
        conn.commit()
    try:
        text = read_document_text(doc)
        pieces = chunk_markdown(text)
        model = kb["embedding_model"] if kb else MODEL_ID
        if model != MODEL_ID:
            raise ExecutionError("INVALID_ARGUMENTS", "This worker embeds with feature-hash-v1 only.", False)
        with connect() as conn:
            conn.execute("delete from document_chunks where document_id = %s", (document_id,))
            for piece in pieces:
                vector = embed(piece["content"])
                conn.execute(
                    """
                    insert into document_chunks (
                        id, document_id, chunk_index, section, page_number, content, embedding, embedding_model
                    ) values (%s, %s, %s, %s, %s, %s, %s::vector, %s)
                    """,
                    (
                        str(uuid.uuid4()), document_id, piece["chunk_index"], piece["section"], piece["page_number"],
                        piece["content"], to_pgvector(vector), MODEL_ID,
                    ),
                )
            conn.execute("update documents set status = 'ACTIVE', error_message = null where id = %s", (document_id,))
            conn.commit()
    except Exception as exc:
        with connect() as conn:
            conn.execute("update documents set status = 'FAILED', error_message = %s where id = %s", (str(exc)[:400], document_id))
            conn.commit()
        raise


def read_document_text(doc) -> str:
    key = doc["storage_key"]
    if key.startswith("seed://"):
        return (SEED_DIR / key.removeprefix("seed://")).read_text(encoding="utf-8")
    if doc["media_type"] == "application/pdf":
        data = download(key)
        from pypdf import PdfReader
        import io
        reader = PdfReader(io.BytesIO(data))
        return "\n".join(page.extract_text() or "" for page in reader.pages)
    return download(key).decode("utf-8")


def download(key: str) -> bytes:
    client = s3()
    obj = client.get_object(Bucket=os.environ.get("S3_BUCKET", "aegistrace-documents"), Key=key)
    return obj["Body"].read()


def s3():
    return boto3.client(
        "s3",
        endpoint_url=os.environ.get("S3_ENDPOINT"),
        aws_access_key_id=os.environ.get("S3_ACCESS_KEY"),
        aws_secret_access_key=os.environ.get("S3_SECRET_KEY"),
        region_name=os.environ.get("S3_REGION", "us-east-1"),
        config=Config(s3={"addressing_style": "path"}),
    )


def seed_knowledge() -> None:
    with connect() as conn:
        kb = conn.execute("select id from knowledge_bases where slug = 'support-policies'").fetchone()
        if kb is None:
            return
        count = conn.execute(
            """
            select count(*) as n from documents
            where knowledge_base_id = %s and status = 'ACTIVE'
            """,
            (kb["id"],),
        ).fetchone()["n"]
        if count >= 11:
            return
        workspace = conn.execute("select workspace_id from knowledge_bases where id = %s", (kb["id"],)).fetchone()["workspace_id"]
        for path in sorted(SEED_DIR.glob("*.md")):
            document_id = str(uuid.uuid5(uuid.NAMESPACE_URL, path.name))
            title = path.read_text(encoding="utf-8").splitlines()[0].lstrip("# ").strip()
            conn.execute(
                """
                insert into documents (
                    id, workspace_id, knowledge_base_id, title, media_type, storage_key, checksum_sha256, status
                ) values (%s, %s, %s, %s, 'text/markdown', %s, %s, 'UPLOADED')
                on conflict (id) do nothing
                """,
                (document_id, workspace, kb["id"], title, f"seed://{path.name}", "seed"),
            )
        conn.commit()
    with connect() as conn:
        docs = conn.execute("select id from documents where status = 'UPLOADED' and storage_key like 'seed://%'").fetchall()
    for doc in docs:
        embed_document(str(doc["id"]))


def evaluate_run(run_id: str) -> None:
    with connect() as conn:
        run = conn.execute("select * from agent_runs where id = %s", (run_id,)).fetchone()
        if run is None:
            return
        citations = run["citations"] if isinstance(run["citations"], list) else json.loads(run["citations"] or "[]")
        answer = run["final_response"] or run["draft_answer"] or ""
        scores = {
            "groundedness": _groundedness(answer, citations, conn),
            "citation": 1.0 if citations and all("quote" in c for c in citations) else (1.0 if not citations and "don't have enough" in answer else 0.0),
            "abstention": 1.0 if ("don't have enough documented evidence" in answer) == (not citations) else 0.5,
            "completion": 1.0 if run["state"] in {"COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"} else 0.0,
            "policy": _policy_score(conn, run_id),
            "idempotency": _idempotency_score(conn, run_id),
        }
        passed = min(scores.values()) >= 0.5 and scores["policy"] == 1.0
        conn.execute(
            """
            insert into evaluations (
                id, workspace_id, run_id, agent_version_id, prompt_version_id, model,
                dataset_version, evaluator_version, scores, passed
            ) values (%s, %s, %s, %s, %s, %s, 'support-v1', 'heuristic-v1', %s::jsonb, %s)
            on conflict (run_id, evaluator_version) do nothing
            """,
            (
                str(uuid.uuid4()), run["workspace_id"], run_id, run["agent_version_id"], run["prompt_version_id"],
                run["model"], json.dumps(scores), passed,
            ),
        )
        conn.commit()


def _groundedness(answer: str, citations: list, conn) -> float:
    if "don't have enough documented evidence" in answer:
        return 1.0
    if not citations:
        return 0.0
    hits = 0
    for citation in citations:
        row = conn.execute("select content from document_chunks where id = %s", (citation.get("chunkId"),)).fetchone()
        quote = citation.get("quote") or ""
        if row and quote and quote in row["content"]:
            hits += 1
    return hits / len(citations)


def _policy_score(conn, run_id: str) -> float:
    tickets = conn.execute("select count(*) as n from tickets where run_id = %s", (run_id,)).fetchone()["n"]
    approved = conn.execute(
        "select count(*) as n from approvals where run_id = %s and status = 'APPROVED'", (run_id,)
    ).fetchone()["n"]
    if tickets and not approved:
        return 0.0
    if tickets > 1:
        return 0.0
    return 1.0


def _idempotency_score(conn, run_id: str) -> float:
    rows = conn.execute(
        "select count(*) as n from tickets where run_id = %s", (run_id,)
    ).fetchone()["n"]
    return 1.0 if rows <= 1 else 0.0


if __name__ == "__main__":
    main()
