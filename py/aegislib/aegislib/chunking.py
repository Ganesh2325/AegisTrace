from __future__ import annotations

import re

_HEADING = re.compile(r"^(#{1,3})\s+(.*)$", re.M)


def chunk_markdown(text: str, size: int = 900, overlap: int = 150) -> list[dict]:
    """Split markdown into section-aware windows. Page is the window index for non-PDF text."""
    if not text or not text.strip():
        return []
    sections: list[tuple[str, str]] = []
    matches = list(_HEADING.finditer(text))
    if not matches:
        sections.append(("", text.strip()))
    else:
        if matches[0].start() > 0:
            preamble = text[: matches[0].start()].strip()
            if preamble:
                sections.append(("", preamble))
        for i, match in enumerate(matches):
            start = match.end()
            end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
            body = text[start:end].strip()
            title = match.group(2).strip()
            sections.append((title, f"{title}. {body}" if body else title))

    chunks: list[dict] = []
    for section, body in sections:
        start = 0
        while start < len(body):
            window = body[start : start + size].strip()
            if window:
                chunks.append(
                    {
                        "section": section,
                        "content": window,
                        "page_number": len(chunks) + 1,
                    }
                )
            if start + size >= len(body):
                break
            start += max(1, size - overlap)
    for index, chunk in enumerate(chunks):
        chunk["chunk_index"] = index
    return chunks


def chunk_plain(text: str, size: int = 900, overlap: int = 150) -> list[dict]:
    return chunk_markdown(text, size=size, overlap=overlap)
