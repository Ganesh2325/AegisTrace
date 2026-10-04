"""Call a running API and write the measurements. Do not edit the output by hand."""

from __future__ import annotations

import json
import os
import statistics
import time
import urllib.request
from pathlib import Path

BASE = os.environ.get("AEGIS_BENCH_URL", "http://localhost:8080")
OUT = Path(__file__).resolve().parents[1] / "benchmarks" / "results" / "api.json"


def sample(path: str, n: int = 20) -> dict:
    durations = []
    errors = 0
    for _ in range(n):
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(BASE + path, timeout=5) as response:
                response.read()
                if response.status >= 400:
                    errors += 1
        except Exception:
            errors += 1
        durations.append((time.perf_counter() - started) * 1000)
    durations.sort()
    def pct(p: float) -> float:
        if not durations:
            return 0
        index = min(len(durations) - 1, int(round(p * (len(durations) - 1))))
        return round(durations[index], 2)
    return {
        "path": path,
        "samples": n,
        "errors": errors,
        "p50_ms": pct(0.50),
        "p95_ms": pct(0.95),
        "p99_ms": pct(0.99),
        "mean_ms": round(statistics.fmean(durations), 2) if durations else None,
    }


def main() -> None:
    report = {
        "base": BASE,
        "note": "Health-endpoint samples only. This does not include model latency.",
        "results": [sample("/liveness"), sample("/readiness")],
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(OUT.read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
