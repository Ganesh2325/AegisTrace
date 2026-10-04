"""Feature hashing embeddings.

This is a real retrieval method (signed feature hashing of tokens and bigrams),
not a neural embedding and not a placeholder that returns zeros. Knowledge bases
record the model id so these vectors are never mixed with another embedder.
"""

from __future__ import annotations

import hashlib
import math
import re

DIM = 384
MODEL_ID = "feature-hash-v1"

_TOKEN = re.compile(r"[a-z0-9]+")


def tokenize(text: str) -> list[str]:
    return _TOKEN.findall(text.lower())


def embed(text: str, dim: int = DIM) -> list[float]:
    vec = [0.0] * dim
    tokens = tokenize(text)
    if not tokens:
        return vec
    grams = list(tokens)
    grams.extend(f"{a}_{b}" for a, b in zip(tokens, tokens[1:]))
    for gram in grams:
        digest = hashlib.blake2b(gram.encode("utf-8"), digest_size=8).digest()
        bucket = int.from_bytes(digest[:4], "little") % dim
        sign = 1.0 if digest[4] % 2 == 0 else -1.0
        vec[bucket] += sign
    norm = math.sqrt(sum(v * v for v in vec)) or 1.0
    return [v / norm for v in vec]


def cosine(a: list[float], b: list[float]) -> float:
    if len(a) != len(b) or not a:
        return 0.0
    return float(sum(x * y for x, y in zip(a, b)))


def to_pgvector(vec: list[float]) -> str:
    return "[" + ",".join(f"{v:.6f}" for v in vec) + "]"
