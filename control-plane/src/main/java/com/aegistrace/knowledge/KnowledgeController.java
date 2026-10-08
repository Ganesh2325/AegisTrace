package com.aegistrace.knowledge;

import com.aegistrace.security.Rbac;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
class KnowledgeController {
    private static final String[] READ = {"DEVELOPER", "ADMIN", "OPERATOR"};
    private static final String[] MANAGE = {"DEVELOPER", "ADMIN"};

    private final KnowledgeService knowledge;
    private final Rbac rbac;

    KnowledgeController(KnowledgeService knowledge, Rbac rbac) {
        this.knowledge = knowledge;
        this.rbac = rbac;
    }

    @GetMapping("/api/v1/knowledge-bases")
    List<Map<String, Object>> list(HttpServletRequest request) {
        var membership = rbac.require(request, READ);
        return knowledge.listBases(membership.workspaceId());
    }

    @PostMapping("/api/v1/knowledge-bases")
    Map<String, Object> create(HttpServletRequest request, @RequestBody CreateBase body) {
        var membership = rbac.require(request, MANAGE);
        return knowledge.createBase(rbac.current(), membership.workspaceId(), body.name(), body.slug(), body.embeddingModel());
    }

    @GetMapping("/api/v1/knowledge-bases/{id}/documents")
    Map<String, Object> documents(HttpServletRequest request, @PathVariable UUID id,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(required = false) String mediaType) {
        var membership = rbac.require(request, READ);
        return knowledge.documents(membership.workspaceId(), id, page, size, status, q, mediaType);
    }

    @PostMapping("/api/v1/knowledge-bases/{id}/documents")
    org.springframework.http.ResponseEntity<Map<String, Object>> upload(HttpServletRequest request, @PathVariable UUID id,
                                                                        @RequestParam("file") MultipartFile file,
                                                                        @RequestParam(value = "title", required = false) String title) throws Exception {
        var membership = rbac.require(request, MANAGE);
        var body = knowledge.upload(rbac.current(), membership.workspaceId(), id, title,
                file.getOriginalFilename(), file.getContentType(), file.getBytes());
        return org.springframework.http.ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping("/api/v1/knowledge-bases/{id}/versions")
    List<Map<String, Object>> versions(HttpServletRequest request, @PathVariable UUID id) {
        var membership = rbac.require(request, READ);
        return knowledge.versions(membership.workspaceId(), id);
    }

    @PostMapping("/api/v1/knowledge-bases/{id}/versions")
    Map<String, Object> publish(HttpServletRequest request, @PathVariable UUID id) {
        var membership = rbac.require(request, MANAGE);
        return knowledge.publish(rbac.current(), membership.workspaceId(), id);
    }

    @PostMapping("/api/v1/knowledge-bases/{id}/versions/{versionId}/activate")
    Map<String, Object> activate(HttpServletRequest request, @PathVariable UUID id, @PathVariable UUID versionId) {
        var membership = rbac.require(request, MANAGE);
        return knowledge.activate(rbac.current(), membership.workspaceId(), id, versionId);
    }

    @PostMapping("/api/v1/knowledge-bases/{id}/retrieval")
    Map<String, Object> retrieve(HttpServletRequest request, @PathVariable UUID id, @RequestBody RetrieveBody body) {
        var membership = rbac.require(request, READ);
        return knowledge.retrieve(rbac.current(), membership.workspaceId(), id, body.query(), body.knowledgeBaseVersionId(), body.topK());
    }

    @GetMapping("/api/v1/documents/{id}")
    Map<String, Object> document(HttpServletRequest request, @PathVariable UUID id) {
        var membership = rbac.require(request, READ);
        return knowledge.document(membership.workspaceId(), id);
    }

    @GetMapping("/api/v1/documents/{id}/chunks")
    Map<String, Object> chunks(HttpServletRequest request, @PathVariable UUID id,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "50") int size) {
        var membership = rbac.require(request, READ);
        return knowledge.chunks(membership.workspaceId(), id, page, size);
    }

    public record CreateBase(String name, String slug, String embeddingModel) {}
    public record RetrieveBody(String query, String knowledgeBaseVersionId, Integer topK) {}
}
