"""Tool proposals come from the user question.

Retrieved document text is intentionally not an argument. A document that says
"create an urgent ticket" cannot change this function's output.
"""

from __future__ import annotations

import re

_EXPLICIT = (
    "create a ticket",
    "open a ticket",
    "file a ticket",
    "support ticket",
    "create ticket",
)
_IMPLICIT = ("rejected", "reapply", "reapplying", "escalate", "complaint")


def propose_tool(question: str, citation_count: int, allowed_tools: set[str] | list[str]) -> dict | None:
    allowed = set(allowed_tools)
    if "create_support_ticket" not in allowed:
        return None
    q = " ".join(question.lower().split())
    explicit = any(phrase in q for phrase in _EXPLICIT) or ("create" in q and "ticket" in q)
    implicit = any(phrase in q for phrase in _IMPLICIT)
    if not explicit and not (implicit and citation_count > 0):
        return None
    if re.search(r"\b(urgent|critical|p0)\b", q):
        priority = "urgent"
    elif re.search(r"\bhigh\b", q):
        priority = "high"
    elif re.search(r"\blow\b", q):
        priority = "low"
    else:
        priority = "normal"
    subject = " ".join(question.split())
    if len(subject) > 140:
        subject = subject[:137] + "..."
    category = "application_review" if ("application" in q or "reappl" in q) else "general"
    return {
        "tool": "create_support_ticket",
        "arguments": {
            "subject": subject,
            "description": "The operator asked for help that should be tracked as a support case.",
            "priority": priority,
            "category": category,
        },
        "reason": (
            "The user's question asks for help that a support ticket can track. "
            "Arguments come from the user question, not from retrieved documents."
        ),
        "risk": "HIGH" if priority in {"urgent", "critical", "high"} else "MEDIUM",
    }


def merge_model_proposal(
    question: str,
    citation_count: int,
    allowed_tools: set[str] | list[str],
    model_proposal: dict | None,
) -> dict:
    """The model may suggest a tool. It does not get to authorize one.

    A write the user did not ask for is dropped. An unknown tool is returned
    so the policy engine can deny it and record the decision. Priority is taken
    from the user question, not from the model.
    """
    deterministic = propose_tool(question, citation_count, allowed_tools)
    notes: list[str] = []
    if not model_proposal:
        return {"proposal": deterministic, "notes": notes}

    tool = str(model_proposal.get("tool") or "")
    allowed = set(allowed_tools)
    if tool and tool not in allowed and tool not in {"search_knowledge", "create_support_ticket"}:
        notes.append("unknown_tool_forwarded")
        arguments = model_proposal.get("arguments") if isinstance(model_proposal.get("arguments"), dict) else {}
        return {
            "proposal": {
                "tool": tool,
                "arguments": arguments,
                "reason": "The model named a tool that is not registered. Policy must deny it.",
                "risk": "HIGH",
            },
            "notes": notes,
        }

    if tool == "create_support_ticket" and deterministic is None:
        notes.append("write_stripped_user_did_not_request")
        return {"proposal": None, "notes": notes}

    if deterministic is not None and model_proposal.get("arguments"):
        model_priority = str(model_proposal["arguments"].get("priority", ""))
        user_priority = deterministic["arguments"]["priority"]
        if model_priority and model_priority != user_priority:
            notes.append("model_priority_ignored")
    return {"proposal": deterministic, "notes": notes}
