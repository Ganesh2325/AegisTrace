"""Live local security checks for the authenticated control plane.

The script uses the development accounts and prints statuses only. It does
not print session tokens, passwords, or response bodies that may contain
product data.
"""

from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from reliability_benchmark import Client


def status_of(request: urllib.request.Request) -> tuple[int, dict[str, str], bytes]:
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read()


def cookie_status(client: Client, path: str, headers: dict[str, str]) -> int:
    request = urllib.request.Request(client.base_url + path, method="GET", headers=headers)
    try:
        with client.opener.open(request, timeout=10) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


def main() -> None:
    origin = "http://localhost:3000"
    base = "http://localhost:8080"
    password = "change-me-dev-password"
    operator = Client(base, "dev.operator@aegistrace.local", password, origin)
    reviewer = Client(base, "dev.reviewer@aegistrace.local", password, origin)
    developer = Client(base, "dev.developer@aegistrace.local", password, origin)
    checks: list[tuple[str, bool, str]] = []

    def record(name: str, ok: bool, detail: str) -> None:
        checks.append((name, ok, detail))

    anonymous, _, _ = status_of(urllib.request.Request(base + "/api/v1/runs"))
    record("unauthenticated API rejected", anonymous == 401, f"status={anonymous}")

    evil, evil_headers, _ = status_of(urllib.request.Request(
        base + "/api/v1/runs", data=b"{}", method="POST",
        headers={"Origin": "https://evil.example", "Content-Type": "application/json"},
    ))
    record("malicious origin rejected", evil == 403, f"status={evil}")
    record("nosniff header", evil_headers.get("X-Content-Type-Options") == "nosniff", evil_headers.get("X-Content-Type-Options", ""))
    record("frame denial", evil_headers.get("X-Frame-Options") == "DENY", evil_headers.get("X-Frame-Options", ""))
    record("api content policy", "frame-ancestors 'none'" in evil_headers.get("Content-Security-Policy", ""), "present" if evil_headers.get("Content-Security-Policy") else "missing")

    missing, _, _ = status_of(urllib.request.Request(
        base + "/api/v1/runs", data=b"{}", method="POST", headers={"Content-Type": "application/json"},
    ))
    record("missing origin rejected", missing == 403, f"status={missing}")

    record("operator can read own run list", operator.get("/api/v1/runs?page=0&size=1")[0] == 200, f"status={operator.get('/api/v1/runs?page=0&size=1')[0]}")
    record("operator cannot administer users", operator.get("/api/v1/admin/users")[0] == 403, f"status={operator.get('/api/v1/admin/users')[0]}")
    record("operator cannot review approvals", operator.get("/api/v1/approvals?page=0&size=1")[0] == 403, f"status={operator.get('/api/v1/approvals?page=0&size=1')[0]}")
    record("operator cannot read evaluations", operator.get("/api/v1/evaluation/runs?page=0")[0] == 403, f"status={operator.get('/api/v1/evaluation/runs?page=0')[0]}")
    record("reviewer cannot read audit", reviewer.get("/api/v1/audit?window=7D&page=0")[0] == 403, f"status={reviewer.get('/api/v1/audit?window=7D&page=0')[0]}")
    created = reviewer._request("/api/v1/agents", method="POST", body={"name": "escalation"}, timeout=10)
    record("reviewer cannot create agents", created[0] == 403, f"status={created[0]}")
    record("developer cannot administer users", developer.get("/api/v1/admin/users")[0] == 403, f"status={developer.get('/api/v1/admin/users')[0]}")

    foreign = str(uuid.uuid4())
    unknown = operator.get(f"/api/v1/runs/{foreign}")
    record("unknown run is not found", unknown[0] in {404, 403}, f"status={unknown[0]}")
    forged = cookie_status(operator, "/api/v1/runs", {"X-Workspace-Id": foreign, "Origin": origin})
    record("forged workspace rejected", forged == 403, f"status={forged}")

    login_status, _, login_body = status_of(urllib.request.Request(
        base + "/api/v1/auth/login",
        data=json.dumps({"email": "dev.admin@aegistrace.local", "password": "incorrect-password"}).encode(),
        method="POST",
        headers={"Origin": origin, "Content-Type": "application/json"},
    ))
    record(
        "login failure is generic",
        login_status == 401 and b"password_hash" not in login_body and b"Exception" not in login_body,
        f"status={login_status}",
    )

    passed = sum(1 for _, ok, _ in checks if ok)
    print(json.dumps({
        "passed": passed,
        "total": len(checks),
        "checks": [{"name": name, "ok": ok, "detail": detail} for name, ok, detail in checks],
    }, indent=2))
    if passed != len(checks):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
