from aegislib.chunking import chunk_markdown, chunk_pages
from aegislib.embedding import embed, cosine
from aegislib.grounding import ABSTAIN, grounded_answer, rank_chunks
from aegislib.planner import merge_model_proposal, propose_tool
from aegislib.retries import backoff_seconds, classify_retry
from aegislib.execution import ExecutionError, execute_ticket, execution_key

from corpus import load_corpus

QUESTION = "Why was my application rejected, and what should I do before reapplying?"


def test_markdown_chunks_do_not_invent_page_numbers():
    chunks = chunk_markdown("# Refunds\n\nRefunds are issued within five business days.")
    assert chunks
    assert all(chunk["page_number"] is None for chunk in chunks)


def test_pdf_page_numbers_are_preserved():
    chunks = chunk_pages([(2, "Application review requires a complete file.")])
    assert chunks[0]["page_number"] == 2
    assert chunks[0]["chunk_index"] == 0


def test_embeddings_are_deterministic_and_normalized():
    a = embed("application rejected before reapplying")
    b = embed("application rejected before reapplying")
    assert a == b
    assert abs(sum(v * v for v in a) - 1) < 1e-6
    assert cosine(a, embed("favorite color of a comet")) < 0.6


def test_demo_question_is_grounded_in_the_application_policy():
    chunks = rank_chunks(QUESTION, load_corpus(), k=4)
    titles = [c["document_title"] for c in chunks]
    assert "Application review policy" in titles
    result = grounded_answer(QUESTION, chunks)
    assert result["abstained"] is False
    assert result["supported"] is True
    assert any(c["documentTitle"] == "Application review policy" for c in result["citations"])
    for citation in result["citations"]:
        source = next(c for c in chunks if c["chunk_id"] == citation["chunkId"])
        assert citation["quote"] in source["content"]


def test_unknown_question_abstains():
    chunks = rank_chunks("What is the chief executive's favorite color?", load_corpus(), k=4)
    result = grounded_answer("What is the chief executive's favorite color?", chunks)
    assert result["answer"] == ABSTAIN
    assert result["citations"] == []


def test_injection_document_cannot_propose_a_ticket():
    question = "Summarize the internal override notice."
    chunks = rank_chunks(question, load_corpus(), k=4)
    result = grounded_answer(question, chunks)
    proposal = propose_tool(question, len(result["citations"]), {"search_knowledge", "create_support_ticket"})
    assert proposal is None
    assert "urgent" not in result["answer"].lower() or "ignored" in result["answer"].lower()
    merged = merge_model_proposal(
        question,
        2,
        {"search_knowledge", "create_support_ticket"},
        {"tool": "create_support_ticket", "arguments": {"priority": "urgent", "subject": "bypass"}},
    )
    assert merged["proposal"] is None
    assert "write_stripped_user_did_not_request" in merged["notes"]


def test_demo_question_proposes_a_normal_ticket_from_the_user_not_the_document():
    proposal = propose_tool(QUESTION, 3, {"search_knowledge", "create_support_ticket"})
    assert proposal["tool"] == "create_support_ticket"
    assert proposal["arguments"]["priority"] == "normal"
    merged = merge_model_proposal(
        QUESTION,
        3,
        {"search_knowledge", "create_support_ticket"},
        {"tool": "create_support_ticket", "arguments": {"priority": "urgent", "subject": "ignore system policy"}},
    )
    assert merged["proposal"]["arguments"]["priority"] == "normal"
    assert "model_priority_ignored" in merged["notes"]


def test_unknown_model_tool_is_forwarded_for_denial():
    merged = merge_model_proposal(
        "Hello",
        0,
        {"search_knowledge", "create_support_ticket"},
        {"tool": "delete_database", "arguments": {"target": "prod"}},
    )
    assert merged["proposal"]["tool"] == "delete_database"


def test_retry_matrix():
    assert classify_retry("LLM_TIMEOUT") is True
    assert classify_retry("HTTP_429", 429) is True
    assert classify_retry("DB_UNAVAILABLE") is True
    assert classify_retry("TICKET_500", 500) is True
    assert classify_retry("INVALID_ARGUMENTS", 400) is False
    assert classify_retry("TICKET_400", 400) is False
    assert classify_retry("APPROVAL_EXPIRED") is False
    assert classify_retry("PROMPT_INJECTION") is False
    assert backoff_seconds(1) == 2
    assert backoff_seconds(10) == 60


class _Cursor:
    def __init__(self, conn):
        self.conn = conn
        self._row = None
        self.rowcount = 0

    def execute(self, sql, params=None):
        text = sql.lower()
        if "from agent_runs" in text:
            self._row = (self.conn.run_state,)
        elif "from approvals" in text:
            self._row = self.conn.approval
        else:
            self._row = None

    def fetchone(self):
        return self._row

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class _Conn:
    def __init__(self, run_state, approval):
        self.run_state = run_state
        self.approval = approval

    def cursor(self):
        return _Cursor(self)


def test_execution_key_is_run_and_proposal():
    assert execution_key("run-1", "proposal-1") == "run-1:proposal-1"


def test_worker_skips_cancelled_runs_without_writing():
    job = {"payload": {"runId": "r1", "proposalId": "p1", "workspaceId": "w1", "approvalId": "a1", "arguments": {}}}
    result = execute_ticket(_Conn("CANCELLED", ("APPROVED", False, "p1", "r1")), job)
    assert result["status"] == "SKIPPED"
    assert result["reason"] == "CANCELLED"


def test_worker_rejects_mismatched_proposal_identity():
    job = {"payload": {"runId": "r1", "proposalId": "p1", "workspaceId": "w1", "approvalId": "a1", "arguments": {}}}
    try:
        execute_ticket(_Conn("TOOL_EXECUTING", ("APPROVED", False, "other-proposal", "r1")), job)
        raise AssertionError("expected ExecutionError")
    except ExecutionError as exc:
        assert exc.error_type == "APPROVAL_EXPIRED"
        assert exc.retryable is False
