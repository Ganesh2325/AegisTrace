package com.aegistrace.audit;

import com.aegistrace.common.Jsons;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(UUID workspaceId, UUID actorId, String action, String resourceType, String resourceId,
                       UUID runId, Map<String, Object> metadata) {
        var params = new HashMap<String, Object>();
        params.put("id", UUID.randomUUID());
        params.put("workspaceId", workspaceId);
        params.put("actorId", actorId);
        params.put("action", action);
        params.put("resourceType", resourceType);
        params.put("resourceId", resourceId);
        params.put("traceId", MDC.get("trace_id"));
        params.put("runId", runId);
        params.put("requestId", MDC.get("request_id"));
        params.put("metadata", Jsons.jsonb(Jsons.write(mapper, metadata == null ? Map.of() : metadata)));
        jdbc.update("""
                insert into audit_events (
                    id, workspace_id, actor_id, action, resource_type, resource_id, trace_id, run_id, request_id, metadata
                ) values (
                    :id, :workspaceId, :actorId, :action, :resourceType, :resourceId, :traceId, :runId, :requestId, :metadata
                )
                """, params);
    }
}
