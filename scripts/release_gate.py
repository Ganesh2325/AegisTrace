"""Decide whether a deployment may proceed from a health response.

The script prints only the decision. It does not print response bodies.
"""

from __future__ import annotations

import argparse
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "py" / "aegislib"))
from aegislib.production import promotion_decision


def status_of(url: str) -> int:
    try:
        with urllib.request.urlopen(url, timeout=5) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except Exception:
        return 0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    args = parser.parse_args()
    decision = promotion_decision(status_of(args.url))
    print(decision)
    if decision != "promote":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
