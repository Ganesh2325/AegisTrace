"""OpenTelemetry helpers shared by the runtime and worker.

Span attributes are an allowlist. Prometheus is not configured here: these
processes export traces only, so run and trace identifiers never become
metric labels.
"""

from __future__ import annotations

import os
import re
from contextlib import contextmanager
from typing import Iterator

from opentelemetry import trace
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.trace import (
    NonRecordingSpan,
    SpanContext,
    Status,
    StatusCode,
    TraceFlags,
    set_span_in_context,
)

_HEX_32 = re.compile(r"^[0-9a-f]{32}$")
_HEX_16 = re.compile(r"^[0-9a-f]{16}$")

ALLOWED_ATTRIBUTES = frozenset({
    "run.id",
    "workspace.id",
    "agent.id",
    "agent.version_id",
    "knowledge.version_id",
    "tool.name",
    "policy.decision",
    "policy.code",
    "service",
    "environment",
    "status",
    "job.type",
    "retrieval.strategy",
    "retrieval.chunk_count",
    "retrieval.candidates",
    "model.provider",
    "model.name",
    "model.invoked",
    "error.type",
})

_BLOCKED_FRAGMENTS = (
    "prompt", "question", "content", "quote", "argument", "password",
    "secret", "token", "authorization", "user.id", "user_id", "body",
)


def safe_attributes(raw: dict | None) -> dict[str, str | int | float | bool]:
    out: dict[str, str | int | float | bool] = {}
    if not raw:
        return out
    for key, value in raw.items():
        if not isinstance(key, str) or key not in ALLOWED_ATTRIBUTES:
            continue
        lowered = key.lower()
        if any(fragment in lowered for fragment in _BLOCKED_FRAGMENTS):
            continue
        if isinstance(value, bool):
            out[key] = value
        elif isinstance(value, int) and not isinstance(value, bool):
            out[key] = value
        elif isinstance(value, float):
            out[key] = value
        elif isinstance(value, str):
            text = value.strip()
            if not text or len(text) > 120:
                continue
            out[key] = text
    return out


def normalize_hex(value: str | None, width: int) -> str | None:
    if not value:
        return None
    hex_id = value.replace("-", "").strip().lower()
    if not re.fullmatch(r"[0-9a-f]+", hex_id) or len(hex_id) > width:
        return None
    padded = hex_id.zfill(width)
    if set(padded) == {"0"}:
        return None
    return padded


def traceparent(trace_id: str | None, span_id: str | None, sampled: bool = True) -> str | None:
    trace_hex = normalize_hex(trace_id, 32)
    span_hex = normalize_hex(span_id, 16)
    if trace_hex is None or span_hex is None:
        return None
    return f"00-{trace_hex}-{span_hex}-{'01' if sampled else '00'}"


def context_from_traceparent(header: str | None):
    parsed = parse_traceparent(header)
    if parsed is None:
        return None
    trace_hex, span_hex, flags = parsed
    span_context = SpanContext(
        trace_id=int(trace_hex, 16),
        span_id=int(span_hex, 16),
        is_remote=True,
        trace_flags=TraceFlags(flags),
    )
    return set_span_in_context(NonRecordingSpan(span_context))


def parse_traceparent(header: str | None) -> tuple[str, str, int] | None:
    if not header:
        return None
    parts = header.strip().split("-")
    if len(parts) != 4 or parts[0] != "00":
        return None
    trace_hex = normalize_hex(parts[1], 32)
    span_hex = normalize_hex(parts[2], 16)
    if trace_hex is None or span_hex is None or not re.fullmatch(r"[0-9a-f]{2}", parts[3].lower()):
        return None
    return trace_hex, span_hex, int(parts[3], 16)


def consumer_context(trace_id: str | None, span_id: str | None):
    """Parent the worker span on the stored run span.

    Missing span id returns None so the worker starts a new trace instead of
    inventing a parent. The run id attribute still correlates the work.
    """
    return context_from_traceparent(traceparent(trace_id, span_id, True))


def current_traceparent() -> str | None:
    span = trace.get_current_span()
    context = span.get_span_context()
    if context is None or not context.is_valid:
        return None
    return f"00-{context.trace_id:032x}-{context.span_id:016x}-{int(context.trace_flags):02x}"


def configure(service_name: str) -> None:
    endpoint = os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT", "").strip()
    provider = TracerProvider(resource=Resource.create({"service.name": service_name}))
    if endpoint:
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
        from opentelemetry.sdk.trace.export import BatchSpanProcessor

        base = endpoint.removesuffix("/v1/traces").rstrip("/")
        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=f"{base}/v1/traces")))
    trace.set_tracer_provider(provider)


def testing_provider():
    from opentelemetry.sdk.trace.export import SimpleSpanProcessor
    from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
    from opentelemetry.util._once import Once
    import opentelemetry.trace as trace_api

    exporter = InMemorySpanExporter()
    provider = TracerProvider(resource=Resource.create({"service.name": "test"}))
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    # Tests replace the process-wide provider. The API allows that only once
    # unless the guard is reset.
    if hasattr(trace_api, "_TRACER_PROVIDER_SET_ONCE"):
        trace_api._TRACER_PROVIDER_SET_ONCE = Once()
    if hasattr(trace_api, "_TRACER_PROVIDER"):
        trace_api._TRACER_PROVIDER = None
    trace.set_tracer_provider(provider)
    return exporter


@contextmanager
def span(name: str, attributes: dict | None = None, context=None) -> Iterator[trace.Span]:
    tracer = trace.get_tracer("aegistrace")
    with tracer.start_as_current_span(name, context=context) as current:
        for key, value in safe_attributes(attributes).items():
            current.set_attribute(key, value)
        try:
            yield current
        except Exception as exc:
            current.set_status(Status(StatusCode.ERROR))
            current.set_attribute("error.type", type(exc).__name__)
            raise


def set_safe(current: trace.Span, key: str, value) -> None:
    safe = safe_attributes({key: value})
    if key in safe:
        current.set_attribute(key, safe[key])
