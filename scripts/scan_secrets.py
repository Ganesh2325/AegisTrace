"""Fail if the tree contains likely secrets. Development placeholders in .env.example are allowed."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SKIP = {".git", "node_modules", "target", ".next", "__pycache__"}
PATTERNS = [
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"-----BEGIN (RSA |OPENSSH |EC )?PRIVATE KEY-----"),
    re.compile(r"sk-[A-Za-z0-9]{20,}"),
    re.compile(r"xox[baprs]-[A-Za-z0-9-]{10,}"),
]

def main() -> int:
    hits = []
    for path in ROOT.rglob("*"):
        if not path.is_file() or any(part in SKIP for part in path.parts):
            continue
        if path.suffix.lower() in {".png", ".jpg", ".jar", ".woff", ".woff2"}:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for pattern in PATTERNS:
            if pattern.search(text):
                hits.append(f"{path.relative_to(ROOT)} matches {pattern.pattern}")
    if hits:
        print("\n".join(hits))
        return 1
    print("secret scan: no matches")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
