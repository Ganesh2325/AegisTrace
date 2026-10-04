from aegislib.embedding import embed, cosine
from aegislib.grounding import ABSTAIN, grounded_answer, rank_chunks
from aegislib.planner import merge_model_proposal, propose_tool
from aegislib.retries import backoff_seconds, classify_retry

from corpus import load_corpus

QUESTION = "Why was my application rejected, and what should I do before reapplying?"


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
