"""Retry classification. Prompt injection is not an error class the worker retries."""

from __future__ import annotations

import random


def classify_retry(error_type: str, status_code: int | None = None) -> bool:
    name = (error_type or "").upper()
    if name in {
        "INVALID_ARGUMENTS",
        "TICKET_400",
        "APPROVAL_EXPIRED",
        "APPROVAL_REJECTED",
        "PROMPT_INJECTION",
        "POLICY_DENIED",
        "CANCELLED",
        "BUDGET_EXCEEDED",
    }:
        return False
    if status_code is not None:
        if status_code == 429 or status_code >= 500:
            return True
        if 400 <= status_code < 500:
            return False
    if name in {"LLM_TIMEOUT", "MODEL_TIMEOUT", "HTTP_429", "DB_UNAVAILABLE", "TICKET_500", "TIMEOUT", "DEPENDENCY_UNAVAILABLE"}:
        return True
    return False


def backoff_seconds(attempt: int, cap: int = 60) -> int:
    if attempt < 1:
        attempt = 1
    return min(cap, 2 ** attempt)


def jittered_backoff_seconds(attempt: int, cap: int = 60, *, random_value: float | None = None) -> float:
    """Return bounded exponential backoff with equal jitter.

    ``random_value`` exists so reliability tests can verify exact bounds without
    depending on process-global random state.
    """
    base = float(backoff_seconds(attempt, cap))
    sample = random.random() if random_value is None else min(max(random_value, 0.0), 1.0)
    return (base / 2.0) + (base / 2.0 * sample)
