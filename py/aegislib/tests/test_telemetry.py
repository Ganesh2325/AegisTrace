import os

from opentelemetry import metrics

from aegislib.telemetry import (
    ALLOWED_ATTRIBUTES,
    consumer_context,
    current_traceparent,
    parse_traceparent,
    safe_attributes,
    set_safe,
    span,
    testing_provider as memory_exporter,
    traceparent,
)


def test_safe_attributes_drop_prompts_secrets_and_unknown_keys():
    safe = safe_attributes({
        "run.id": "run-1",
        "tool.name": "create_support_ticket",
        "prompt": "hidden question",
        "question": "what is the refund policy",
        "content": "full document",
        "arguments": {"body": "secret"},
        "password": "x",
        "authorization": "Bearer abc",
        "user.id": "user-1",
        "chunk.body": "retrieved text",
        "note": "not on the allowlist",
    })
    assert safe == {"run.id": "run-1", "tool.name": "create_support_ticket"}
    assert "prompt" not in ALLOWED_ATTRIBUTES
    assert "user.id" not in ALLOWED_ATTRIBUTES


def test_traceparent_round_trip_and_rejects_zero_ids():
    header = traceparent("abc123abc123abc123abc123abc123ab", "0123456789abcdef", True)
    assert header == "00-abc123abc123abc123abc123abc123ab-0123456789abcdef-01"
    assert parse_traceparent(header)[0] == "abc123abc123abc123abc123abc123ab"
    assert traceparent("0" * 32, "0123456789abcdef") is None
    assert parse_traceparent("not-a-header") is None
    assert consumer_context("abc", None) is None


def test_exported_spans_keep_only_safe_attributes_and_continue_parent():
    exporter = memory_exporter()
    parent = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01"
    with span("aegistrace.runtime.execute", {
        "run.id": "run-9",
        "service": "agent-runtime",
        "prompt": "do not export",
        "question": "raw question",
    }, context=consumer_context("a" * 32, "b" * 16)):
        with span("aegistrace.retrieval.search", {"retrieval.strategy": "hybrid_rrf", "content": "chunk text"}):
            set_safe(current_span(), "retrieval.chunk_count", 3)
            set_safe(current_span(), "prompt", "nope")
    finished = exporter.get_finished_spans()
    names = [item.name for item in finished]
    assert names == ["aegistrace.retrieval.search", "aegistrace.runtime.execute"]
    outer = finished[1]
    assert format(outer.context.trace_id, "032x") == "a" * 32
    assert format(outer.parent.span_id, "016x") == "b" * 16
    assert outer.attributes["run.id"] == "run-9"
    assert "prompt" not in outer.attributes
    assert "question" not in outer.attributes
    inner = finished[0]
    assert inner.attributes["retrieval.chunk_count"] == 3
    assert "content" not in inner.attributes
    assert "prompt" not in inner.attributes
    assert parse_traceparent(parent) is not None


def test_missing_span_id_does_not_invent_a_parent():
    exporter = memory_exporter()
    assert consumer_context("c" * 32, None) is None
    with span("aegistrace.worker.job", {"run.id": "run-2", "job.type": "EXECUTE_TOOL", "service": "worker"}):
        pass
    finished = exporter.get_finished_spans()
    assert finished[0].parent is None
    assert format(finished[0].context.trace_id, "032x") != "c" * 32


def test_current_traceparent_matches_active_span():
    memory_exporter()
    with span("aegistrace.tool.execute", {"tool.name": "create_support_ticket", "service": "worker"}):
        header = current_traceparent()
    parsed = parse_traceparent(header)
    assert parsed is not None
    assert len(parsed[0]) == 32
    assert len(parsed[1]) == 16


def test_python_telemetry_does_not_install_a_metric_provider():
    provider = metrics.get_meter_provider()
    assert "Prometheus" not in type(provider).__name__
    assert os.environ.get("OTEL_METRICS_EXPORTER", "") != "prometheus"


def current_span():
    from opentelemetry import trace
    return trace.get_current_span()
