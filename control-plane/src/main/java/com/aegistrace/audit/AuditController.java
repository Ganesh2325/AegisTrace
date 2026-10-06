package com.aegistrace.audit;

import com.aegistrace.security.Rbac;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit")
class AuditController {
    private final AuditQueryService audit;
    private final Rbac rbac;

    AuditController(AuditQueryService audit, Rbac rbac) {
        this.audit = audit;
        this.rbac = rbac;
    }

    @GetMapping
    Map<String, Object> list(HttpServletRequest request,
                             @RequestParam(defaultValue = "7D") String window,
                             @RequestParam(required = false) String action,
                             @RequestParam(required = false) String actor,
                             @RequestParam(required = false) String resourceType,
                             @RequestParam(required = false) String result,
                             @RequestParam(required = false) String runId,
                             @RequestParam(required = false) String approvalId,
                             @RequestParam(required = false) String q,
                             @RequestParam(defaultValue = "0") int page) {
        var membership = rbac.require(request, AuditAccess.READ_ROLES);
        return audit.list(membership.workspaceId(), window, action, actor, resourceType, result, runId, approvalId, q, page);
    }

    @GetMapping("/{id}")
    Map<String, Object> get(HttpServletRequest request, @PathVariable UUID id) {
        var membership = rbac.require(request, AuditAccess.READ_ROLES);
        return audit.get(membership.workspaceId(), id);
    }
}
