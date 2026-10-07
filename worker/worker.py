"""Background worker. Postgres is the queue. Tickets are idempotent."""

from __future__ import annotations

import hashlib
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

from aegislib.chunking import chunk_pages, normalize_text
from aegislib.embedding import MODEL_ID, embed, to_pgvector
from aegislib.execution import ExecutionError, execute_ticket
from aegislib.evaluation import evaluate_product_run
from aegislib.retries import backoff_seconds, classify_retry
from aegislib.telemetry import configure, consumer_context, current_traceparent, span

configure("worker")

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
        try:
            job = claim()
        except Exception as exc:
            print(f"claim_failed {exc.__class__.__name__}")
            time.sleep(1)
            continue
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
    terminal = not retryable or attempt >= int(job["max_attempts"])
    with connect() as conn:
        conn.execute(
            """
            insert into job_attempts (id, job_id, attempt, error_type, error_message, retryable)
            values (%s, %s, %s, %s, %s, %s)
            """,
            (str(uuid.uuid4()), job["id"], attempt, error_type, message, retryable),
        )
        if not terminal:
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
    if terminal:
        payload = job["payload"] if isinstance(job["payload"], dict) else json.loads(job["payload"])
        try:
            if job["job_type"] == "EVALUATE_CASE":
                internal_post(f"/internal/evaluation-results/{payload['evaluationResultId']}/complete", {
                    "status": "ERROR",
                    "score": None,
                    "failureCategory": error_type,
                    "explanation": "Evaluation infrastructure exhausted its retry budget.",
                    "checks": [{
                        "key": "infrastructure.worker",
                        "status": "ERROR",
                        "score": None,
                        "failureCategory": error_type,
                        "explanation": "Evaluation infrastructure exhausted its retry budget.",
                        "evidence": {"errorType": error_type},
                    }],
                    "signals": [],
                })
            elif job["job_type"] == "START_EVALUATION":
                internal_post(f"/internal/evaluations/{payload['evaluationExecutionId']}/failed", {
                    "errorType": error_type,
                })
        except Exception as callback_error:
            print(f"evaluation_failure_callback_failed {callback_error.__class__.__name__}")


def process(job) -> None:
    payload = job["payload"] if isinstance(job["payload"], dict) else json.loads(job["payload"])
    job["payload"] = payload
    print(
        f"job_claimed type={job['job_type']} run_id={payload.get('runId')} "
        f"trace_id={payload.get('traceId')} proposal_id={payload.get('proposalId')}"
    )
    parent = consumer_context(str(payload.get("traceId") or ""), str(payload.get("spanId") or ""))
    attributes = {
        "service": "worker",
        "job.type": job["job_type"],
        "run.id": str(payload.get("runId") or ""),
        "workspace.id": str(payload.get("workspaceId") or ""),
        "environment": os.environ.get("AEGIS_ENVIRONMENT", "dev"),
    }
    if job["job_type"] == "EXECUTE_TOOL":
        attributes["tool.name"] = str(payload.get("tool") or "")
    with span("aegistrace.worker.job", attributes, context=parent):
        if job["job_type"] == "EMBED_DOCUMENT":
            embed_document(payload["documentId"])
        elif job["job_type"] == "EXECUTE_TOOL":
            with span("aegistrace.tool.execute", {
                "service": "worker",
                "tool.name": str(payload.get("tool") or ""),
                "run.id": str(payload.get("runId") or ""),
                "workspace.id": str(payload.get("workspaceId") or ""),
            }):
                execute_and_callback(job)
        elif job["job_type"] == "EVALUATE_RUN":
            evaluate_run(payload["runId"])
        elif job["job_type"] == "START_EVALUATION":
            internal_post(f"/internal/evaluations/{payload['evaluationExecutionId']}/dispatch", {})
        elif job["job_type"] == "EVALUATE_CASE":
            evaluate_case(payload["runId"], payload["evaluationResultId"])
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
    headers = {"X-Internal-Token": TOKEN}
    parent = current_traceparent()
    if parent:
        headers["traceparent"] = parent
    with httpx.Client(timeout=10) as client:
        response = client.post(
            f"{CONTROL_PLANE}/internal/runs/{run_id}/tool-result",
            headers=headers,
            json=body,
        )
        response.raise_for_status()


def internal_post(path: str, body: dict) -> None:
    headers = {"X-Internal-Token": TOKEN}
    parent = current_traceparent()
    if parent:
        headers["traceparent"] = parent
    with httpx.Client(timeout=30) as client:
        response = client.post(f"{CONTROL_PLANE}{path}", headers=headers, json=body)
        response.raise_for_status()


def embed_document(document_id: str) -> None:
    with connect() as conn:
        doc = conn.execute("select * from documents where id = %s", (document_id,)).fetchone()
        if doc is None:
            raise ExecutionError("INVALID_ARGUMENTS", "Document does not exist.", False)
        locked = conn.execute(
            "select exists(select 1 from knowledge_version_documents where document_id = %s) as locked",
            (document_id,),
        ).fetchone()["locked"]
        if doc["status"] == "ACTIVE" and locked:
            return
        conn.execute("update documents set status = 'PROCESSING', error_message = null where id = %s", (document_id,))
        kb = conn.execute("select embedding_model from knowledge_bases where id = %s", (doc["knowledge_base_id"],)).fetchone()
        conn.commit()
    try:
        pages = read_document_pages(doc)
        pieces = chunk_pages(pages)
        if not pieces:
            raise ExecutionError("EXTRACTION_FAILED", "No text could be extracted from the document.", False)
        model = kb["embedding_model"] if kb else MODEL_ID
        if model != MODEL_ID:
            raise ExecutionError("INVALID_ARGUMENTS", "This worker embeds with feature-hash-v1 only.", False)
        with connect() as conn:
            if locked:
                raise ExecutionError("CONFLICT", "Published knowledge versions cannot be re-embedded in place.", False)
            conn.execute("delete from document_chunks where document_id = %s", (document_id,))
            batch = []
            for piece in pieces:
                batch.append((piece, embed(piece["content"])))
            for piece, vector in batch:
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


def read_document_pages(doc) -> list[tuple[int | None, str]]:
    key = doc["storage_key"]
    if key.startswith("seed://"):
        text = (SEED_DIR / key.removeprefix("seed://")).read_text(encoding="utf-8")
        return [(None, normalize_text(text))]
    if doc["media_type"] == "application/pdf":
        data = download(key)
        from pypdf import PdfReader
        import io
        reader = PdfReader(io.BytesIO(data))
        pages = []
        for index, page in enumerate(reader.pages, start=1):
            pages.append((index, normalize_text(page.extract_text() or "")))
        if not any(text for _, text in pages):
            raise ExecutionError("EXTRACTION_FAILED", "The PDF contained no extractable text.", False)
        return pages
    text = download(key).decode("utf-8")
    return [(None, normalize_text(text))]


def read_document_text(doc) -> str:
    return "\n\n".join(text for _, text in read_document_pages(doc) if text)


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
        kb_id = str(kb["id"])
        count = conn.execute(
            """
            select count(*) as n from documents
            where knowledge_base_id = %s and status = 'ACTIVE'
            """,
            (kb["id"],),
        ).fetchone()["n"]
        if count >= 11:
            refresh_seed_checksums()
            attach_current_version_if_empty(kb_id)
            return
        workspace = conn.execute("select workspace_id from knowledge_bases where id = %s", (kb["id"],)).fetchone()["workspace_id"]
        for path in sorted(SEED_DIR.glob("*.md")):
            document_id = str(uuid.uuid5(uuid.NAMESPACE_URL, path.name))
            raw = path.read_bytes()
            title = raw.decode("utf-8").splitlines()[0].lstrip("# ").strip()
            checksum = hashlib.sha256(raw).hexdigest()
            conn.execute(
                """
                insert into documents (
                    id, workspace_id, knowledge_base_id, title, media_type, storage_key, checksum_sha256, byte_size, status
                ) values (%s, %s, %s, %s, 'text/markdown', %s, %s, %s, 'UPLOADED')
                on conflict (id) do nothing
                """,
                (document_id, workspace, kb["id"], title, f"seed://{path.name}", checksum, len(raw)),
            )
        conn.commit()
    with connect() as conn:
        docs = conn.execute("select id from documents where status = 'UPLOADED' and storage_key like 'seed://%'").fetchall()
    for doc in docs:
        embed_document(str(doc["id"]))
    attach_current_version_if_empty(kb_id)


def refresh_seed_checksums() -> None:
    with connect() as conn:
        for path in sorted(SEED_DIR.glob("*.md")):
            raw = path.read_bytes()
            checksum = hashlib.sha256(raw).hexdigest()
            conn.execute(
                """
                update documents
                set checksum_sha256 = %s, byte_size = %s
                where storage_key = %s and (checksum_sha256 = 'seed' or byte_size = 0)
                """,
                (checksum, len(raw), f"seed://{path.name}"),
            )
        conn.commit()


def attach_current_version_if_empty(knowledge_base_id: str) -> None:
    with connect() as conn:
        current = conn.execute(
            """
            select id from knowledge_base_versions
            where knowledge_base_id = %s and current_version
            """,
            (knowledge_base_id,),
        ).fetchone()
        if current is None:
            return
        members = conn.execute(
            "select count(*) as n from knowledge_version_documents where knowledge_base_version_id = %s",
            (current["id"],),
        ).fetchone()["n"]
        if members > 0:
            return
        conn.execute(
            """
            insert into knowledge_version_documents (knowledge_base_version_id, document_id)
            select %s, id from documents
            where knowledge_base_id = %s and status = 'ACTIVE'
            on conflict do nothing
            """,
            (current["id"], knowledge_base_id),
        )
        conn.commit()


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
            ) values (%s, %s, %s, %s, %s, %s, 'live-run-v1', 'heuristic-v1', %s::jsonb, %s)
            on conflict (run_id, evaluator_version) do nothing
            """,
            (
                str(uuid.uuid4()), run["workspace_id"], run_id, run["agent_version_id"], run["prompt_version_id"],
                run["model"], json.dumps(scores), passed,
            ),
        )
        conn.commit()


def evaluate_case(run_id: str, result_id: str) -> None:
    with connect() as conn:
        outcome = evaluate_product_run(conn, run_id, result_id)
    internal_post(f"/internal/evaluation-results/{result_id}/complete", outcome)


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
