package com.aegistrace.runtime;

import com.aegistrace.common.ApiException;
import com.aegistrace.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RuntimeClient {
    private final AppProperties properties;
    private final ObjectMapper mapper;

    public RuntimeClient(AppProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> retrieve(Map<String, Object> body) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(15));
        RestClient client = RestClient.builder()
                .baseUrl(properties.getRuntimeUrl())
                .requestFactory(factory)
                .build();
        try {
            JsonNode node = client.post()
                    .uri("/v1/retrieve")
                    .header("X-Internal-Token", properties.getInternalToken())
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            var hits = new ArrayList<Map<String, Object>>();
            if (node != null && node.has("hits") && node.get("hits").isArray()) {
                node.get("hits").forEach(item -> hits.add(mapper.convertValue(item, Map.class)));
            }
            return hits;
        } catch (RestClientResponseException ex) {
            throw new RuntimeCallException("DEPENDENCY_UNAVAILABLE", "Retrieval is unavailable.", false);
        } catch (ResourceAccessException ex) {
            throw new RuntimeCallException("DEPENDENCY_UNAVAILABLE", "Retrieval timed out.", true);
        }
    }

    public Plan plan(Map<String, Object> body, int timeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofMillis(Math.max(timeoutMs, 1000)));
        RestClient client = RestClient.builder()
                .baseUrl(properties.getRuntimeUrl())
                .requestFactory(factory)
                .build();
        try {
            JsonNode node = client.post()
                    .uri("/v1/plan")
                    .header("X-Internal-Token", properties.getInternalToken())
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (node == null) {
                throw new RuntimeCallException("DEPENDENCY_UNAVAILABLE", "The agent runtime returned an empty response.", true);
            }
            return Plan.from(node, mapper);
        } catch (RestClientResponseException ex) {
            boolean retry = ex.getStatusCode().value() == 429 || ex.getStatusCode().is5xxServerError();
            throw new RuntimeCallException(retry ? "MODEL_TIMEOUT" : "INTERNAL", "The agent runtime rejected the plan request.", retry);
        } catch (ResourceAccessException ex) {
            throw new RuntimeCallException("MODEL_TIMEOUT", "The agent runtime timed out.", true);
        }
    }

    public record Plan(
            String answer,
            boolean supported,
            boolean abstained,
            boolean uncertain,
            List<Map<String, Object>> citations,
            int retrievedChunkCount,
            int inputTokens,
            int outputTokens,
            long modelLatencyMs,
            long retrievalLatencyMs,
            String provider,
            String model,
            Map<String, Object> toolProposal,
            List<String> notes
    ) {
        static Plan from(JsonNode node, ObjectMapper mapper) {
            var citations = new ArrayList<Map<String, Object>>();
            if (node.has("citations") && node.get("citations").isArray()) {
                node.get("citations").forEach(item -> citations.add(mapper.convertValue(item, Map.class)));
            }
            JsonNode usage = node.path("usage");
            JsonNode proposal = node.get("toolProposal");
            Map<String, Object> proposalMap = proposal == null || proposal.isNull() ? null : mapper.convertValue(proposal, Map.class);
            var notes = new ArrayList<String>();
            if (node.has("notes") && node.get("notes").isArray()) {
                node.get("notes").forEach(item -> notes.add(item.asText()));
            }
            return new Plan(
                    node.path("answer").asText(""),
                    node.path("supported").asBoolean(false),
                    node.path("abstained").asBoolean(false),
                    node.path("uncertain").asBoolean(false),
                    citations,
                    node.path("retrievedChunkCount").asInt(0),
                    usage.path("inputTokens").asInt(0),
                    usage.path("outputTokens").asInt(0),
                    usage.path("latencyMs").asLong(0),
                    usage.path("retrievalLatencyMs").asLong(0),
                    usage.path("provider").asText(""),
                    usage.path("model").asText(""),
                    proposalMap,
                    notes
            );
        }
    }

    public static class RuntimeCallException extends ApiException {
        private final boolean retryable;
        private final String category;

        public RuntimeCallException(String category, String message, boolean retryable) {
            super(category, message, 503);
            this.category = category;
            this.retryable = retryable;
        }

        public boolean isRetryable() { return retryable; }
        public String getCategory() { return category; }
    }
}
