package com.aegistrace.agent;

import com.aegistrace.security.Rbac;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class AgentController {
    private static final String[] VIEW = {"DEVELOPER", "ADMIN", "OPERATOR", "REVIEWER"};
    private static final String[] CONFIGURE = {"DEVELOPER", "ADMIN"};

    private final AgentService agents;
    private final Rbac rbac;

    AgentController(AgentService agents, Rbac rbac) {
        this.agents = agents;
        this.rbac = rbac;
    }

    @GetMapping("/agents")
    List<Map<String, Object>> list(HttpServletRequest request) {
        var membership = rbac.require(request, VIEW);
        return agents.list(membership.workspaceId());
    }

    @GetMapping("/agents/{id}")
    Map<String, Object> get(HttpServletRequest request, @PathVariable UUID id) {
        var membership = rbac.require(request, VIEW);
        return agents.get(rbac.current(), membership.workspaceId(), id);
    }

    @PostMapping("/agents")
    Map<String, Object> create(HttpServletRequest request, @RequestBody CreateAgent body) {
        var membership = rbac.require(request, CONFIGURE);
        return agents.create(rbac.current(), membership.workspaceId(), body.name(), body.description());
    }

    @PostMapping("/agents/{id}/versions")
    Map<String, Object> version(HttpServletRequest request, @PathVariable UUID id, @RequestBody VersionRequest body) {
        var membership = rbac.require(request, CONFIGURE);
        return agents.createVersion(rbac.current(), membership.workspaceId(), id, body.toCreate());
    }

    @PostMapping("/agents/{id}/versions/{versionId}/activate")
    Map<String, Object> activate(HttpServletRequest request, @PathVariable UUID id, @PathVariable UUID versionId) {
        var membership = rbac.require(request, CONFIGURE);
        return agents.activate(rbac.current(), membership.workspaceId(), id, versionId);
    }

    @PostMapping("/agents/{id}/status")
    Map<String, Object> status(HttpServletRequest request, @PathVariable UUID id, @RequestBody StatusRequest body) {
        var membership = rbac.require(request, CONFIGURE);
        return agents.status(rbac.current(), membership.workspaceId(), id, body.status());
    }

    @GetMapping("/agents/{id}/versions")
    List<Map<String, Object>> versions(HttpServletRequest request, @PathVariable UUID id) {
        var membership = rbac.require(request, VIEW);
        return agents.versions(membership.workspaceId(), id);
    }

    @GetMapping("/agents/{id}/versions/{versionId}")
    Map<String, Object> version(HttpServletRequest request, @PathVariable UUID id, @PathVariable UUID versionId) {
        var membership = rbac.require(request, VIEW);
        return agents.version(membership.workspaceId(), id, versionId);
    }

    @GetMapping("/agents/{id}/audit")
    List<Map<String, Object>> audit(HttpServletRequest request, @PathVariable UUID id) {
        var membership = rbac.require(request, "DEVELOPER", "ADMIN");
        return agents.auditTrail(membership.workspaceId(), id);
    }

    @GetMapping("/tools")
    List<Map<String, Object>> tools(HttpServletRequest request) {
        rbac.require(request, CONFIGURE);
        return agents.tools();
    }

    public record CreateAgent(@NotBlank String name, String description) {}
    public record StatusRequest(String status) {}
    public record VersionRequest(
            String provider, String model, double temperature, int maxTokens, int timeoutMs, int maxToolCalls,
            BigDecimal costBudgetUsd, int tokenBudget, String systemPrompt, String promptVersionId, String knowledgeBaseId,
            List<String> toolNames, String environment
    ) {
        AgentService.CreateVersion toCreate() {
            return new AgentService.CreateVersion(
                    provider, model, temperature, maxTokens, timeoutMs, maxToolCalls, costBudgetUsd, tokenBudget,
                    systemPrompt, promptVersionId, knowledgeBaseId, toolNames, environment);
        }
    }
}
