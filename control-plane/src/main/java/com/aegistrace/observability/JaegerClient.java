package com.aegistrace.observability;

import com.aegistrace.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class JaegerClient {
    private static final Logger log = LoggerFactory.getLogger(JaegerClient.class);
    private final RestClient traceClient;
    private final RestClient jaegerStatusClient;
    private final RestClient prometheusStatusClient;

    public JaegerClient(AppProperties properties) {
        this.traceClient = client(properties.getJaegerQueryUrl(), Duration.ofSeconds(2), Duration.ofSeconds(3));
        this.jaegerStatusClient = client(properties.getJaegerQueryUrl(), Duration.ofSeconds(1), Duration.ofSeconds(2));
        this.prometheusStatusClient = client(properties.getPrometheusUrl(), Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    public TraceFetch fetch(String traceId) {
        if (traceClient == null) {
            return TraceFetch.notConfigured();
        }
        if (!TelemetryPrivacy.safeTraceId(TelemetryPrivacy.hexTraceId(traceId))) {
            return TraceFetch.unavailable("Trace ID is not a Jaeger hex identifier.", 0);
        }
        long requestStarted = System.nanoTime();
        try {
            JsonNode body = traceClient.get()
                    .uri("/api/traces/{id}", TelemetryPrivacy.hexTraceId(traceId))
                    .retrieve()
                    .body(JsonNode.class);
            double httpDurationMs = elapsedMs(requestStarted);
            List<Map<String, Object>> spans = parse(body);
            if (spans.isEmpty()) {
                return TraceFetch.empty(httpDurationMs);
            }
            return TraceFetch.ok(spans, httpDurationMs);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            double httpDurationMs = elapsedMs(requestStarted);
            if (ex.getStatusCode().value() == 404) {
                return TraceFetch.empty(httpDurationMs);
            }
            log.warn("jaeger_unavailable error_type={}", ex.getClass().getSimpleName());
            return TraceFetch.unavailable("The trace backend did not respond.", httpDurationMs);
        } catch (RestClientException ex) {
            log.warn("jaeger_unavailable error_type={}", ex.getClass().getSimpleName());
            return TraceFetch.unavailable("The trace backend did not respond.", elapsedMs(requestStarted));
        }
    }

    private static double elapsedMs(long started) {
        return Math.round((System.nanoTime() - started) / 10_000.0) / 100.0;
    }

    public String tracesBackendStatus() {
        if (jaegerStatusClient == null) {
            return "NOT_CONFIGURED";
        }
        try {
            jaegerStatusClient.get().uri("/api/services").retrieve().toBodilessEntity();
            return "OK";
        } catch (RestClientException ex) {
            log.warn("jaeger_unavailable error_type={}", ex.getClass().getSimpleName());
            return "UNAVAILABLE";
        }
    }

    public String prometheusStatus() {
        if (prometheusStatusClient == null) {
            return "NOT_CONFIGURED";
        }
        try {
            prometheusStatusClient.get().uri("/-/ready").retrieve().toBodilessEntity();
            return "OK";
        } catch (RestClientException ex) {
            log.warn("prometheus_unavailable error_type={}", ex.getClass().getSimpleName());
            return "UNAVAILABLE";
        }
    }

    private static RestClient client(String url, Duration connectTimeout, Duration readTimeout) {
        if (url == null || url.isBlank()) {
            return null;
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url.trim();
        return RestClient.builder().baseUrl(base).requestFactory(factory).build();
    }

    static String parentSpanId(JsonNode span) {
        JsonNode references = span.path("references");
        if (!references.isArray() || references.isEmpty()) {
            return "";
        }
        for (JsonNode reference : references) {
            if ("CHILD_OF".equals(reference.path("refType").asText())) {
                return reference.path("spanID").asText("");
            }
        }
        return references.get(0).path("spanID").asText("");
    }

    static List<Map<String, Object>> parse(JsonNode body) {
        var spans = new ArrayList<Map<String, Object>>();
        if (body == null || !body.has("data") || !body.get("data").isArray() || body.get("data").isEmpty()) {
            return spans;
        }
        JsonNode trace = body.get("data").get(0);
        JsonNode processes = trace.path("processes");
        JsonNode rawSpans = trace.path("spans");
        if (!rawSpans.isArray()) {
            return spans;
        }
        int n = 0;
        for (JsonNode span : rawSpans) {
            if (n++ >= 200) {
                break;
            }
            var tags = new LinkedHashMap<String, Object>();
            if (span.has("tags") && span.get("tags").isArray()) {
                for (JsonNode tag : span.get("tags")) {
                    String key = tag.path("key").asText();
                    if (TelemetryPrivacy.blocked(key)) {
                        continue;
                    }
                    JsonNode value = tag.get("value");
                    if (value == null || value.isContainerNode()) {
                        continue;
                    }
                    if (value.isTextual()) {
                        tags.put(key, value.asText());
                    } else if (value.isNumber()) {
                        tags.put(key, value.numberValue());
                    } else if (value.isBoolean()) {
                        tags.put(key, value.asBoolean());
                    }
                }
            }
            tags = new LinkedHashMap<>(TelemetryPrivacy.attributes(tags));
            String processId = span.path("processID").asText("");
            String service = processes.path(processId).path("serviceName").asText("unknown");
            long startUs = span.path("startTime").asLong(0);
            long durationUs = span.path("duration").asLong(0);
            boolean error = "true".equalsIgnoreCase(String.valueOf(tags.getOrDefault("error", "")))
                    || "2".equals(String.valueOf(tags.getOrDefault("otel.status_code", "")));
            var row = new LinkedHashMap<String, Object>();
            row.put("spanId", span.path("spanID").asText(""));
            row.put("parentSpanId", parentSpanId(span));
            row.put("name", span.path("operationName").asText(""));
            row.put("service", service);
            row.put("kind", "TELEMETRY");
            row.put("startUs", startUs);
            row.put("durationUs", durationUs);
            row.put("durationMs", durationUs / 1000.0);
            row.put("start", startUs == 0 ? null : Instant.ofEpochMilli(startUs / 1000).toString());
            row.put("status", error ? "ERROR" : "OK");
            row.put("attributes", tags);
            spans.add(row);
        }
        return spans;
    }

    public record TraceFetch(String status, String message, List<Map<String, Object>> spans, double httpDurationMs) {
        static TraceFetch ok(List<Map<String, Object>> spans, double httpDurationMs) {
            return new TraceFetch("OK", null, spans, httpDurationMs);
        }

        static TraceFetch empty(double httpDurationMs) {
            return new TraceFetch("EMPTY", "No spans were stored for this identifier.", List.of(), httpDurationMs);
        }

        static TraceFetch unavailable(String message, double httpDurationMs) {
            return new TraceFetch("UNAVAILABLE", message, List.of(), httpDurationMs);
        }

        static TraceFetch notConfigured() {
            return new TraceFetch("NOT_CONFIGURED", "Jaeger query URL is not configured.", List.of(), 0);
        }
    }
}
