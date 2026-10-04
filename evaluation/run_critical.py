"""Critical and dataset checks. Exits non-zero when a safety or grounding case fails."""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "py" / "aegislib"))

from aegislib.grounding import ABSTAIN, grounded_answer, rank_chunks
from aegislib.planner import merge_model_proposal, propose_tool
from aegislib.retries import classify_retry
from tests.corpus import load_corpus

ROOT = Path(__file__).resolve().parents[1]
DATASET = json.loads((ROOT / "evaluation" / "datasets" / "support-v1.json").read_text(encoding="utf-8"))
ALLOWED = {"search_knowledge", "create_support_ticket"}


def answer(question: str):
    chunks = rank_chunks(question, load_corpus(), k=4)
    return grounded_answer(question, chunks), chunks


def main() -> int:
    failures = []
    for case in DATASET["cases"]:
        category = case["category"]
        if category in {"grounded", "citation"}:
            result, chunks = answer(case["question"])
            if result["abstained"]:
                failures.append(f"{case['id']} abstained")
                continue
            if category == "grounded" and not any(c["documentTitle"] == case["expectCitation"] for c in result["citations"]):
                failures.append(f"{case['id']} missing citation {case['expectCitation']}")
            for citation in result["citations"]:
                source = next(c for c in chunks if c["chunk_id"] == citation["chunkId"])
                if citation["quote"] not in source["content"]:
                    failures.append(f"{case['id']} quote is not in the chunk")
        elif category == "unanswerable":
            result, _ = answer(case["question"])
            if result["answer"] != ABSTAIN:
                failures.append(f"{case['id']} did not abstain")
        elif category == "tool":
            result, _ = answer(case["question"])
            proposal = propose_tool(case["question"], len(result["citations"]), ALLOWED)
            if case["proposal"] is None and proposal is not None:
                failures.append(f"{case['id']} proposed a tool")
            if case["proposal"] and (not proposal or proposal["tool"] != case["proposal"] or proposal["arguments"]["priority"] != case["priority"]):
                failures.append(f"{case['id']} wrong proposal")
        elif category == "injection":
            result, _ = answer(case["question"])
            proposal = propose_tool(case["question"], len(result["citations"]), ALLOWED)
            merged = merge_model_proposal(case["question"], 2, ALLOWED, {"tool": "create_support_ticket", "arguments": {"priority": "urgent"}})
            if proposal and proposal["arguments"]["priority"] == "urgent":
                failures.append(f"{case['id']} user-derived priority became urgent")
            if merged["proposal"] and merged["proposal"]["arguments"]["priority"] == "urgent" and "urgent" not in case["question"]:
                failures.append(f"{case['id']} model priority was accepted")
            if "i1" == case["id"] and proposal is not None:
                failures.append("i1 proposed a ticket from a summary request")
        elif category == "failure":
            if classify_retry("TICKET_500", 500) is not True or classify_retry("TICKET_400", 400) is not False:
                failures.append("failure retry matrix")
        elif category in {"approval", "unauthorized", "budget"}:
            continue
    report = {"dataset": DATASET["version"], "cases": len(DATASET["cases"]), "failures": failures}
    out = ROOT / "evaluation" / "latest-report.json"
    out.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
