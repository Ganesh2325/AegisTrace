package com.aegistrace.evaluation;

import com.aegistrace.security.Rbac;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/evaluation")
class EvaluationController {
    private final EvaluationService evaluations;
    private final Rbac rbac;

    EvaluationController(EvaluationService evaluations, Rbac rbac) {
        this.evaluations = evaluations;
        this.rbac = rbac;
    }

    @GetMapping("/overview")
    Map<String, Object> overview(HttpServletRequest request) {
        var membership = rbac.require(request, EvaluationAccess.READ_ROLES);
        return evaluations.overview(membership.workspaceId());
    }

    @GetMapping("/cases")
    Map<String, Object> cases(HttpServletRequest request, @RequestParam(required = false) String category,
                              @RequestParam(defaultValue = "0") int page) {
        var membership = rbac.require(request, EvaluationAccess.READ_ROLES);
        return evaluations.cases(membership.workspaceId(), category, page);
    }

    @PostMapping("/cases")
    ResponseEntity<Map<String, Object>> createCase(HttpServletRequest request,
                                                    @RequestBody EvaluationService.CaseInput body) {
        var membership = rbac.require(request, EvaluationAccess.MANAGE_ROLES);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(evaluations.createCase(rbac.current(), membership.workspaceId(), body));
    }

    @GetMapping("/suites")
    List<Map<String, Object>> suites(HttpServletRequest request) {
        var membership = rbac.require(request, EvaluationAccess.READ_ROLES);
        return evaluations.suites(membership.workspaceId());
    }

    @GetMapping("/suites/{id}")
    Map<String, Object> suite(HttpServletRequest request, @PathVariable UUID id) {
        var membership = rbac.require(request, EvaluationAccess.READ_ROLES);
        return evaluations.suite(membership.workspaceId(), id);
    }

    @PostMapping("/suites")
    ResponseEntity<Map<String, Object>> createSuite(HttpServletRequest request,
                                                     @RequestBody EvaluationService.SuiteInput body) {
        var membership = rbac.require(request, EvaluationAccess.MANAGE_ROLES);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(evaluations.createSuite(rbac.current(), membership.workspaceId(), body));
    }

    @GetMapping("/runs")
    Map<String, Object> executions(HttpServletRequest request, @RequestParam(defaultValue = "0") int page) {
        var membership = rbac.require(request, EvaluationAccess.READ_ROLES);
        return evaluations.executions(membership.workspaceId(), page);
    }

    @PostMapping("/runs")
    ResponseEntity<Map<String, Object>> start(HttpServletRequest request,
                                               @RequestBody EvaluationService.StartInput body) {
        var membership = rbac.require(request, EvaluationAccess.MANAGE_ROLES);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(evaluations.start(rbac.current(), membership.workspaceId(), body));
    }

    @GetMapping("/runs/{id}")
    Map<String, Object> execution(HttpServletRequest request, @PathVariable UUID id) {
        var membership = rbac.require(request, EvaluationAccess.READ_ROLES);
        return evaluations.execution(rbac.current(), membership.workspaceId(), id);
    }

    @PostMapping("/runs/{id}/cancel")
    Map<String, Object> cancel(HttpServletRequest request, @PathVariable UUID id) {
        var membership = rbac.require(request, EvaluationAccess.MANAGE_ROLES);
        return evaluations.cancel(rbac.current(), membership.workspaceId(), id);
    }

    @GetMapping("/compare")
    Map<String, Object> compare(HttpServletRequest request, @RequestParam UUID baseline,
                                @RequestParam UUID candidate) {
        var membership = rbac.require(request, EvaluationAccess.READ_ROLES);
        return evaluations.compare(membership.workspaceId(), baseline, candidate);
    }
}
