"""Bounded local API benchmark and soak harness for AegisTrace.

This script is intentionally read-only apart from creating an authenticated
session. It records user-visible HTTP latency and response bytes without
claiming production-scale capacity.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import http.cookiejar
import json
import math
import statistics
import time
import urllib.error
import urllib.request
from pathlib import Path


ENDPOINTS = {
    "operations_overview": "/api/v1/operations/overview?window=ALL&page=0",
    "support_run_list": "/api/v1/runs?page=0&size=20",
    "approval_list": "/api/v1/approvals?page=0&size=20",
    "knowledge_list": "/api/v1/knowledge-bases",
    "observability_overview": "/api/v1/observability/overview",
    "trace_list": "/api/v1/observability/traces?page=0",
    "audit_list": "/api/v1/audit?window=7D&page=0",
    "evaluation_overview": "/api/v1/evaluation/overview",
    "evaluation_history": "/api/v1/evaluation/runs?page=0",
    "safety_overview": "/api/v1/safety/overview",
}


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    position = (len(ordered) - 1) * p
    low = math.floor(position)
    high = math.ceil(position)
    if low == high:
        return ordered[low]
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


class Client:
    def __init__(self, base_url: str, email: str, password: str, origin: str):
        self.base_url = base_url.rstrip("/")
        self.origin = origin
        jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
        status, payload, _ = self._request(
            "/api/v1/auth/login",
            method="POST",
            body={"email": email, "password": password},
            timeout=30,
        )
        if status != 200:
            raise RuntimeError(f"Login failed with HTTP {status}: {payload[:200]!r}")

    def _request(self, path: str, *, method: str = "GET", body: dict | None = None, timeout: float = 15) -> tuple[int, bytes, float]:
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(
            self.base_url + path,
            data=data,
            method=method,
            headers={
                "Origin": self.origin,
                **({"Content-Type": "application/json"} if data is not None else {}),
            },
        )
        started = time.perf_counter()
        try:
            with self.opener.open(request, timeout=timeout) as response:
                payload = response.read()
                return response.status, payload, (time.perf_counter() - started) * 1000
        except urllib.error.HTTPError as error:
            return error.code, error.read(), (time.perf_counter() - started) * 1000

    def get(self, path: str) -> tuple[int, bytes, float]:
        return self._request(path)


def summarize(samples: list[tuple[int, bytes, float]]) -> dict:
    latencies = [sample[2] for sample in samples]
    errors = sum(1 for status, _, _ in samples if status >= 400)
    timeouts = sum(1 for status, _, _ in samples if status in {408, 504})
    return {
        "requests": len(samples),
        "throughputRps": round(len(samples) / max(sum(latencies) / 1000, 0.001), 2),
        "p50Ms": round(percentile(latencies, 0.50), 2),
        "p95Ms": round(percentile(latencies, 0.95), 2),
        "p99Ms": round(percentile(latencies, 0.99), 2),
        "meanMs": round(statistics.fmean(latencies), 2),
        "errorRate": round(errors / max(len(samples), 1), 4),
        "timeoutRate": round(timeouts / max(len(samples), 1), 4),
        "responseBytes": {
            "min": min((len(payload) for _, payload, _ in samples), default=0),
            "max": max((len(payload) for _, payload, _ in samples), default=0),
        },
        "statuses": {str(code): sum(1 for status, _, _ in samples if status == code) for code in sorted({s[0] for s in samples})},
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--email", default="dev.admin@aegistrace.local")
    parser.add_argument("--password", default="change-me-dev-password")
    parser.add_argument("--origin", default="http://localhost:3000")
    parser.add_argument("--samples", type=int, default=20)
    parser.add_argument("--burst", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--soak-seconds", type=int, default=60)
    parser.add_argument("--include-run-start", action="store_true")
    parser.add_argument("--output", default="evaluation/reliability-benchmark.json")
    args = parser.parse_args()

    client = Client(args.base_url, args.email, args.password, args.origin)
    endpoints = dict(ENDPOINTS)

    def discover(list_path: str, key: str = "items") -> dict:
        status, payload, _ = client.get(list_path)
        if status >= 400:
            return {}
        parsed = json.loads(payload or b"{}")
        if isinstance(parsed, list):
            return parsed[0] if parsed else {}
        rows = parsed.get(key) or []
        return rows[0] if rows else {}

    run = discover(ENDPOINTS["support_run_list"])
    if run.get("id"):
        endpoints["support_run_detail"] = f"/api/v1/runs/{run['id']}/execution"
    approval = discover(ENDPOINTS["approval_list"])
    if approval.get("id"):
        endpoints["approval_detail"] = f"/api/v1/approvals/{approval['id']}"
    trace = discover(ENDPOINTS["trace_list"])
    if trace.get("traceId"):
        endpoints["trace_detail"] = f"/api/v1/observability/traces/{trace['traceId']}"
    audit = discover(ENDPOINTS["audit_list"])
    if audit.get("id"):
        endpoints["audit_detail"] = f"/api/v1/audit/{audit['id']}"
    evaluation = discover(ENDPOINTS["evaluation_history"])
    if evaluation.get("id"):
        endpoints["evaluation_detail"] = f"/api/v1/evaluation/runs/{evaluation['id']}"
    knowledge = discover(ENDPOINTS["knowledge_list"], key="unused")
    if knowledge.get("id"):
        endpoints["knowledge_documents"] = f"/api/v1/knowledge-bases/{knowledge['id']}/documents?page=0&size=20"

    report: dict[str, object] = {
        "measuredAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "baseUrl": args.base_url,
        "method": "Local Docker Compose, authenticated HTTP, sequential endpoint baseline plus bounded concurrent burst and soak.",
        "baseline": {},
    }
    if args.include_run_start:
        agents_status, agents_payload, _ = client.get("/api/v1/agents")
        agents = json.loads(agents_payload or b"[]") if agents_status < 400 else []
        active = next((agent for agent in agents if agent.get("status") == "ACTIVE"), agents[0] if agents else None)
        request_key = f"reliability-benchmark-{int(time.time())}"
        run_body = {
            "question": "What documentation is required for a billing appeal?",
            "requestKey": request_key,
        }
        if active and active.get("id"):
            run_body["agentId"] = active["id"]
        first = client._request("/api/v1/runs", method="POST", body=run_body, timeout=20)
        replay = client._request("/api/v1/runs", method="POST", body=run_body, timeout=20)
        first_json = json.loads(first[1] or b"{}")
        replay_json = json.loads(replay[1] or b"{}")
        report["runStart"] = {
            "firstStatus": first[0],
            "firstMs": round(first[2], 2),
            "replayStatus": replay[0],
            "replayMs": round(replay[2], 2),
            "firstRunId": first_json.get("run", {}).get("id"),
            "replayRunId": replay_json.get("run", {}).get("id"),
            "sameRun": first_json.get("run", {}).get("id") == replay_json.get("run", {}).get("id"),
        }
    for name, path in endpoints.items():
        first = client.get(path)
        warm = [client.get(path) for _ in range(max(1, args.samples))]
        report["baseline"][name] = {
            "firstRequestMs": round(first[2], 2),
            **summarize(warm),
        }

    burst_path = ENDPOINTS["operations_overview"]
    burst_started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.concurrency)) as pool:
        burst_samples = list(pool.map(lambda _: client.get(burst_path), range(max(1, args.burst))))
    burst_summary = summarize(burst_samples)
    burst_summary["wallSeconds"] = round(time.perf_counter() - burst_started, 2)
    burst_summary["throughputRps"] = round(len(burst_samples) / max(burst_summary["wallSeconds"], 0.001), 2)
    report["burst"] = burst_summary

    stop_at = time.monotonic() + max(1, args.soak_seconds)
    soak_samples: list[tuple[int, bytes, float]] = []

    def soak() -> list[tuple[int, bytes, float]]:
        local: list[tuple[int, bytes, float]] = []
        while time.monotonic() < stop_at:
            local.append(client.get(ENDPOINTS["audit_list"]))
        return local

    soak_started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, min(args.concurrency, 4))) as pool:
        for batch in pool.map(lambda _: soak(), range(max(1, min(args.concurrency, 4)))):
            soak_samples.extend(batch)
    soak_summary = summarize(soak_samples)
    soak_summary["wallSeconds"] = round(time.perf_counter() - soak_started, 2)
    soak_summary["throughputRps"] = round(len(soak_samples) / max(soak_summary["wallSeconds"], 0.001), 2)
    report["soak"] = soak_summary

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
