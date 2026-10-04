package com.aegistrace.run;

import com.aegistrace.common.Jsons;
import com.aegistrace.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class RunEventBus {
    private static final Logger log = LoggerFactory.getLogger(RunEventBus.class);
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String instanceId = UUID.randomUUID().toString();
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private RedisClient redis;
    private io.lettuce.core.pubsub.StatefulRedisPubSubConnection<String, String> pubsub;
    private io.lettuce.core.api.StatefulRedisConnection<String, String> commands;

    public RunEventBus(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, AppProperties properties) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        if (!properties.getRedis().isEnabled()) {
            return;
        }
        try {
            var builder = io.lettuce.core.RedisURI.builder()
                    .withHost(properties.getRedis().getHost())
                    .withPort(properties.getRedis().getPort());
            if (properties.getRedis().getPassword() != null && !properties.getRedis().getPassword().isBlank()) {
                builder.withPassword(properties.getRedis().getPassword().toCharArray());
            }
            redis = RedisClient.create(builder.build());
            commands = redis.connect();
            pubsub = redis.connectPubSub();
            pubsub.addListener(new RedisPubSubAdapter<String, String>() {
                @Override
                public void message(String channel, String message) {
                    onRedis(message);
                }
            });
            pubsub.sync().subscribe("aegis.runs");
        } catch (Exception ex) {
            log.warn("redis_fanout_unavailable");
            closeRedis();
        }
    }

    public int append(UUID runId, String eventType, String state, Map<String, Object> payload) {
        Integer sequence = jdbc.queryForObject(
                "update agent_runs set event_seq = event_seq + 1 where id = :id returning event_seq",
                Map.of("id", runId), Integer.class);
        if (sequence == null) {
            throw new IllegalStateException("run missing");
        }
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("runId", runId);
        body.put("sequence", sequence);
        body.put("eventType", eventType);
        body.put("state", state);
        body.put("payload", payload == null ? Map.of() : payload);
        jdbc.update("""
                insert into run_events (id, run_id, sequence, event_type, state, payload)
                values (:id, :runId, :sequence, :eventType, :state, :payload)
                """, Map.of(
                "id", UUID.randomUUID(),
                "runId", runId,
                "sequence", sequence,
                "eventType", eventType,
                "state", state,
                "payload", Jsons.jsonb(Jsons.write(mapper, body.get("payload")))
        ));
        emitLocal(runId, body);
        publish(body);
        return sequence;
    }

    public SseEmitter subscribe(UUID runId, String lastEventId) {
        var emitter = new SseEmitter(30L * 60L * 1000L);
        emitters.computeIfAbsent(runId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(ex -> remove(runId, emitter));
        int after = -1;
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                after = Integer.parseInt(lastEventId);
            } catch (NumberFormatException ignored) {
                after = -1;
            }
        }
        replay(runId, after, emitter);
        return emitter;
    }

    private void replay(UUID runId, int after, SseEmitter emitter) {
        var rows = jdbc.query("""
                select sequence, event_type, state, payload::text as payload, created_at
                from run_events where run_id = :id and sequence > :after order by sequence
                """, Map.of("id", runId, "after", after), (rs, n) -> Map.<String, Object>of(
                "runId", runId,
                "sequence", rs.getInt("sequence"),
                "eventType", rs.getString("event_type"),
                "state", rs.getString("state"),
                "payload", Jsons.map(mapper, rs.getString("payload")),
                "createdAt", rs.getTimestamp("created_at").toInstant().toString()
        ));
        for (var row : rows) {
            send(emitter, row);
        }
    }

    private void publish(Map<String, Object> body) {
        if (commands == null) {
            return;
        }
        try {
            var message = new java.util.LinkedHashMap<String, Object>(body);
            message.put("origin", instanceId);
            commands.sync().publish("aegis.runs", Jsons.write(mapper, message));
        } catch (Exception ex) {
            log.warn("redis_publish_failed");
        }
    }

    private void onRedis(String message) {
        try {
            var body = mapper.readValue(message, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            if (instanceId.equals(body.get("origin"))) {
                return;
            }
            Object runId = body.get("runId");
            if (runId != null) {
                emitLocal(UUID.fromString(runId.toString()), body);
            }
        } catch (Exception ex) {
            log.warn("redis_message_ignored");
        }
    }

    private void emitLocal(UUID runId, Map<String, Object> body) {
        var list = emitters.get(runId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            send(emitter, body);
        }
    }

    private void send(SseEmitter emitter, Map<String, Object> body) {
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(body.get("sequence")))
                    .name(String.valueOf(body.get("eventType")))
                    .data(body, MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // A disconnected subscriber must not fail the transaction that recorded the event.
            }
        }
    }

    private void remove(UUID runId, SseEmitter emitter) {
        var list = emitters.get(runId);
        if (list != null) {
            list.remove(emitter);
        }
    }

    @PreDestroy
    public void closeRedis() {
        try {
            if (pubsub != null) pubsub.close();
            if (commands != null) commands.close();
            if (redis != null) redis.shutdown();
        } catch (Exception ignored) {
            // shutting down
        }
    }
}
