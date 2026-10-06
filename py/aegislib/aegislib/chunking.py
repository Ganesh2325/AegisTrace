from __future__ import annotations

import re

_HEADING = re.compile(r"^(#{1,3})\s+(.*)$", re.M)
_TRAILING = re.compile(r"[ \t]+\n")
_MULTI_NL = re.compile(r"\n{3,}")


def normalize_text(text: str) -> str:
    if not text:
        return ""
    cleaned = text.replace("\r\n", "\n").replace("\r", "\n")
    cleaned = _TRAILING.sub("\n", cleaned)
    cleaned = _MULTI_NL.sub("\n\n", cleaned)
    return cleaned.strip()


def chunk_markdown(text: str, size: int = 900, overlap: int = 150, page_number: int | None = None) -> list[dict]:
    """Split markdown/plain text into section-aware windows of ~900 characters with 150 overlap.

    page_number is only set when the caller extracted a real PDF page. Markdown and text leave it None.
    """
    body_text = normalize_text(text)
    if not body_text:
        return []
    sections: list[tuple[str, str]] = []
    matches = list(_HEADING.finditer(body_text))
    if not matches:
        sections.append(("", body_text))
    else:
        if matches[0].start() > 0:
            preamble = body_text[: matches[0].start()].strip()
            if preamble:
                sections.append(("", preamble))
        for i, match in enumerate(matches):
            start = match.end()
            end = matches[i + 1].start() if i + 1 < len(matches) else len(body_text)
            body = body_text[start:end].strip()
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
                        "page_number": page_number,
                    }
                )
            if start + size >= len(body):
                break
            start += max(1, size - overlap)
    for index, chunk in enumerate(chunks):
        chunk["chunk_index"] = index
    return chunks


def chunk_pages(pages: list[tuple[int | None, str]], size: int = 900, overlap: int = 150) -> list[dict]:
    chunks: list[dict] = []
    for page_number, text in pages:
        for piece in chunk_markdown(text, size=size, overlap=overlap, page_number=page_number):
            chunks.append(piece)
    for index, chunk in enumerate(chunks):
        chunk["chunk_index"] = index
    return chunks


def chunk_plain(text: str, size: int = 900, overlap: int = 150) -> list[dict]:
    return chunk_markdown(text, size=size, overlap=overlap)
