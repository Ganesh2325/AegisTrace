package com.aegistrace.internalapi;

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

    InternalController(RunService runs) {
        this.runs = runs;
    }

    @PostMapping("/runs/{id}/tool-result")
    Map<String, Object> toolResult(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        runs.toolResult(id, body);
        return Map.of("status", "accepted");
    }
}
