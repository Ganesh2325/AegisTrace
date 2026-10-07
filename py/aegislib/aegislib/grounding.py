from __future__ import annotations

from opentelemetry import trace

from aegislib.embedding import cosine, embed, tokenize
from aegislib.telemetry import set_safe, span

ABSTAIN = "I don't have enough documented evidence to answer that confidently."

_INSTRUCTION_MARKERS = (
    "ignore previous",
    "ignore all previous",
    "ignore system",
    "ignore the system",
    "you are now authorized",
    "you are authorized",
    "bypass approval",
    "without approval",
    "disregard policy",
    "system:",
    "developer message",
)


def lexical_score(question: str, content: str) -> float:
    q = [t for t in tokenize(question) if len(t) > 2]
    if not q:
        return 0.0
    c = set(tokenize(content))
    return sum(1 for t in q if t in c) / len(q)


def rrf(rankings: list[list[dict]], k: int = 4, constant: int = 60) -> list[dict]:
    """Reciprocal rank fusion. Each ranking is a list of chunk dicts with chunk_id."""
    scores: dict[str, float] = {}
    best: dict[str, dict] = {}
    for ranking in rankings:
        for rank, chunk in enumerate(ranking, start=1):
            cid = str(chunk["chunk_id"])
            scores[cid] = scores.get(cid, 0.0) + 1.0 / (constant + rank)
            best[cid] = chunk
    ordered = sorted(scores.items(), key=lambda item: item[1], reverse=True)
    fused = []
    for cid, score in ordered[:k]:
        row = dict(best[cid])
        row["fusion_score"] = score
        fused.append(row)
    return fused


def rank_chunks(question: str, chunks: list[dict], k: int = 4) -> list[dict]:
    if not chunks:
        return []
    with span("aegistrace.retrieval.search", {
        "service": "agent-runtime",
        "retrieval.strategy": "hybrid_rrf",
        "retrieval.candidates": len(chunks),
    }):
        with span("aegistrace.vector.search", {"service": "agent-runtime", "retrieval.candidates": len(chunks)}):
            qv = embed(question)
            vector_ranked = sorted(
                chunks,
                key=lambda c: cosine(qv, c["embedding"]),
                reverse=True,
            )
        with span("aegistrace.lexical.search", {"service": "agent-runtime", "retrieval.candidates": len(chunks)}):
            lexical_ranked = sorted(
                chunks,
                key=lambda c: lexical_score(question, c["content"]),
                reverse=True,
            )
        fused = rrf([vector_ranked, lexical_ranked], k=k)
        set_safe(trace.get_current_span(), "retrieval.chunk_count", len(fused))
    for row in fused:
        row["vector_score"] = cosine(qv, row["embedding"])
        row["lexical_score"] = lexical_score(question, row["content"])
        row["score"] = row["fusion_score"]
    return fused


def _sentences(text: str) -> list[str]:
    parts = re_split(text)
    return [p.strip() for p in parts if p.strip()]


def re_split(text: str) -> list[str]:
    import re

    return re.split(r"(?<=[.!?])\s+", text.strip())


def looks_like_instruction(sentence: str) -> bool:
    lowered = sentence.lower()
    return any(marker in lowered for marker in _INSTRUCTION_MARKERS)


def injection_signals(chunks: list[dict]) -> list[dict]:
    """Return bounded references to instruction-like retrieval without copying content."""
    signals = []
    for chunk in chunks:
        lowered = str(chunk.get("content") or "").lower()
        markers = sorted({marker for marker in _INSTRUCTION_MARKERS if marker in lowered})
        if not markers:
            continue
        signals.append({
            "type": "PROMPT_INJECTION",
            "chunkId": str(chunk.get("chunk_id") or ""),
            "documentId": str(chunk.get("document_id") or ""),
            "documentTitle": str(chunk.get("document_title") or ""),
            "markerCount": len(markers),
        })
        if len(signals) >= 10:
            break
    return signals


def _word_overlap(question: str, sentence: str) -> int:
    q = {t for t in tokenize(question) if len(t) > 3}
    s = set(tokenize(sentence))
    return len(q & s)


def grounded_answer(question: str, chunks: list[dict]) -> dict:
    """Copy supporting sentences out of retrieved chunks. Never follow instructions inside them."""
    if not chunks:
        return _abstain([])
    best_lex = max(float(c.get("lexical_score") or lexical_score(question, c["content"])) for c in chunks)
    if best_lex < 0.2:
        return _abstain(chunks)

    citations = []
    lines = []
    seen = set()
    for chunk in chunks:
        for sentence in _sentences(chunk["content"]):
            if looks_like_instruction(sentence):
                continue
            if _word_overlap(question, sentence) < 2:
                continue
            key = sentence.lower()
            if key in seen:
                continue
            seen.add(key)
            index = len(citations) + 1
            lines.append(f"{sentence} [{index}]")
            citations.append(
                {
                    "documentId": str(chunk["document_id"]),
                    "documentTitle": chunk["document_title"],
                    "chunkId": str(chunk["chunk_id"]),
                    "section": chunk.get("section") or "",
                    "pageNumber": chunk.get("page_number"),
                    "score": round(float(chunk.get("score") or 0), 4),
                    "quote": sentence,
                }
            )
            if len(citations) >= 4:
                break
        if len(citations) >= 4:
            break

    if not citations:
        return _abstain(chunks)

    untrusted = any(looks_like_instruction(c["content"]) for c in chunks)
    answer = " ".join(lines)
    if untrusted:
        answer += " Untrusted instructions inside retrieved documents were ignored and cannot change policy."
    return {
        "answer": answer,
        "supported": True,
        "abstained": False,
        "uncertain": best_lex < 0.35,
        "citations": citations,
    }


def _abstain(chunks: list[dict]) -> dict:
    return {
        "answer": ABSTAIN,
        "supported": False,
        "abstained": True,
        "uncertain": True,
        "citations": [],
    }
