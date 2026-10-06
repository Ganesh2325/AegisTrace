package com.aegistrace.observability;

import com.aegistrace.security.Rbac;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/observability")
class ObservabilityController {
    private final ObservabilityService observability;
    private final Rbac rbac;

    ObservabilityController(ObservabilityService observability, Rbac rbac) {
        this.observability = observability;
        this.rbac = rbac;
    }

    @GetMapping("/overview")
    Map<String, Object> overview(HttpServletRequest request, @RequestParam(defaultValue = "24H") String window) {
        var membership = rbac.require(request, ObservabilityAccess.READ_ROLES);
        return observability.overview(rbac.current(), membership.workspaceId(), window);
    }

    @GetMapping("/traces")
    Map<String, Object> traces(HttpServletRequest request,
                               @RequestParam(defaultValue = "24H") String window,
                               @RequestParam(defaultValue = "ALL") String status,
                               @RequestParam(required = false) String runId,
                               @RequestParam(required = false) String traceId,
                               @RequestParam(required = false) String agent,
                               @RequestParam(defaultValue = "0") int page) {
        var membership = rbac.require(request, ObservabilityAccess.READ_ROLES);
        return observability.traces(rbac.current(), membership.workspaceId(), window, status, runId, traceId, agent, page);
    }

    @GetMapping("/traces/{traceId}")
    Map<String, Object> trace(HttpServletRequest request, @PathVariable String traceId) {
        var membership = rbac.require(request, ObservabilityAccess.READ_ROLES);
        return observability.trace(rbac.current(), membership.workspaceId(), traceId);
    }

    @GetMapping("/errors")
    Map<String, Object> errors(HttpServletRequest request,
                               @RequestParam(defaultValue = "24H") String window,
                               @RequestParam(defaultValue = "0") int page) {
        var membership = rbac.require(request, ObservabilityAccess.READ_ROLES);
        return observability.errors(rbac.current(), membership.workspaceId(), window, page);
    }
}
