package com.aegistrace.evaluation;

import com.aegistrace.security.Rbac;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/safety")
class SafetyController {
    private final EvaluationService evaluations;
    private final Rbac rbac;

    SafetyController(EvaluationService evaluations, Rbac rbac) {
        this.evaluations = evaluations;
        this.rbac = rbac;
    }

    @GetMapping("/overview")
    Map<String, Object> overview(HttpServletRequest request) {
        var membership = rbac.require(request, EvaluationAccess.SAFETY_ROLES);
        return evaluations.safetyOverview(rbac.current(), membership.workspaceId());
    }

    @GetMapping("/signals")
    Map<String, Object> signals(HttpServletRequest request,
                                @RequestParam(required = false) String disposition,
                                @RequestParam(defaultValue = "0") int page) {
        var membership = rbac.require(request, EvaluationAccess.SAFETY_ROLES);
        return evaluations.safetySignals(rbac.current(), membership.workspaceId(), disposition, page);
    }
}
