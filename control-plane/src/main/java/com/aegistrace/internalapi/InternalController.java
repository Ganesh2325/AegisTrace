package com.aegistrace.internalapi;

import com.aegistrace.evaluation.EvaluationService;
import com.aegistrace.run.RunService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal")
class InternalController {
    private final RunService runs;
    private final EvaluationService evaluations;

    InternalController(RunService runs, EvaluationService evaluations) {
        this.runs = runs;
        this.evaluations = evaluations;
    }

    @PostMapping("/runs/{id}/tool-result")
    Map<String, Object> toolResult(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        runs.toolResult(id, body);
        return Map.of("status", "accepted");
    }

    @PostMapping("/evaluations/{id}/dispatch")
    Map<String, Object> dispatchEvaluation(@PathVariable UUID id) {
        evaluations.dispatch(id);
        return Map.of("status", "accepted");
    }

    @PostMapping("/evaluations/{id}/failed")
    Map<String, Object> failEvaluation(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        evaluations.failExecution(id, String.valueOf(body.getOrDefault("errorType", "DISPATCH_ERROR")));
        return Map.of("status", "accepted");
    }

    @PostMapping("/evaluation-results/{id}/complete")
    Map<String, Object> completeEvaluationResult(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        evaluations.completeResult(id, body);
        return Map.of("status", "accepted");
    }
}
