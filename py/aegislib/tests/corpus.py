from __future__ import annotations

import uuid
from pathlib import Path

from aegislib.chunking import chunk_markdown
from aegislib.embedding import embed

SEED = Path(__file__).resolve().parents[3] / "knowledge" / "seed"


def load_corpus() -> list[dict]:
    chunks = []
    for path in sorted(SEED.glob("*.md")):
        document_id = str(uuid.uuid5(uuid.NAMESPACE_URL, path.name))
        title = path.read_text(encoding="utf-8").splitlines()[0].lstrip("# ").strip()
        for piece in chunk_markdown(path.read_text(encoding="utf-8")):
            chunk_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{path.name}:{piece['chunk_index']}"))
            chunks.append(
                {
                    "chunk_id": chunk_id,
                    "document_id": document_id,
                    "document_title": title,
                    "section": piece["section"],
                    "page_number": piece["page_number"],
                    "content": piece["content"],
                    "embedding": embed(piece["content"]),
                }
            )
    return chunks
