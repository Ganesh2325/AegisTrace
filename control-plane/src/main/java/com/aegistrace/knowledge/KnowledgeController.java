package com.aegistrace.knowledge;

import com.aegistrace.audit.AuditService;
import com.aegistrace.common.ApiException;
import com.aegistrace.common.Jsons;
import com.aegistrace.security.Rbac;
import com.aegistrace.storage.ObjectStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
class KnowledgeController {
    private static final Set<String> ALLOWED = Set.of("text/markdown", "text/plain", "application/pdf", "text/x-markdown");
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectStore objects;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final Rbac rbac;

    KnowledgeController(NamedParameterJdbcTemplate jdbc, ObjectStore objects, ObjectMapper mapper, AuditService audit, Rbac rbac) {
        this.jdbc = jdbc;
        this.objects = objects;
        this.mapper = mapper;
        this.audit = audit;
        this.rbac = rbac;
    }

    @GetMapping
    List<Map<String, Object>> list(HttpServletRequest request) {
        var membership = rbac.require(request, "DEVELOPER", "ADMIN", "OPERATOR");
        return jdbc.query("""
                select id, name, slug, embedding_model, status from knowledge_bases
                where workspace_id = :workspace order by name
                """, Map.of("workspace", membership.workspaceId()), (rs, n) -> Map.of(
                "id", UUID.fromString(rs.getString("id")),
                "name", rs.getString("name"),
                "slug", rs.getString("slug"),
                "embeddingModel", rs.getString("embedding_model"),
                "status", rs.getString("status")
        ));
    }

    @PostMapping
    Map<String, Object> create(HttpServletRequest request, @RequestBody CreateBase body) {
        var membership = rbac.require(request, "DEVELOPER", "ADMIN");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into knowledge_bases (id, workspace_id, name, slug, embedding_model, embedding_dim)
                values (:id, :workspace, :name, :slug, :model, 384)
                """, Map.of(
                "id", id,
                "workspace", membership.workspaceId(),
                "name", body.name(),
                "slug", body.slug(),
                "model", body.embeddingModel() == null ? "feature-hash-v1" : body.embeddingModel()));
        audit.record(membership.workspaceId(), rbac.current().id(), "KNOWLEDGE_BASE_CREATED", "knowledge_base", id.toString(), null, Map.of());
        return Map.of("id", id);
    }

    @GetMapping("/{id}/documents")
    List<Map<String, Object>> documents(HttpServletRequest request, @PathVariable UUID id) {
        var membership = rbac.require(request, "DEVELOPER", "ADMIN", "OPERATOR");
        return jdbc.query("""
                select d.id, d.title, d.media_type, d.status, d.error_message, d.created_at,
                       (select count(*) from document_chunks c where c.document_id = d.id) as chunks
                from documents d
                where d.knowledge_base_id = :id and d.workspace_id = :workspace
                order by d.created_at desc
                """, Map.of("id", id, "workspace", membership.workspaceId()), (rs, n) -> {
            var row = new java.util.LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("title", rs.getString("title"));
            row.put("mediaType", rs.getString("media_type"));
            row.put("status", rs.getString("status"));
            row.put("errorMessage", rs.getString("error_message"));
            row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            row.put("chunks", rs.getLong("chunks"));
            return row;
        });
    }

    @PostMapping("/{id}/documents")
    org.springframework.http.ResponseEntity<Map<String, Object>> upload(HttpServletRequest request, @PathVariable UUID id,
                                                                        @RequestParam("file") MultipartFile file,
                                                                        @RequestParam("title") String title) throws Exception {
        var membership = rbac.require(request, "DEVELOPER", "ADMIN");
        if (file.getSize() <= 0 || file.getSize() > 10 * 1024 * 1024) {
            throw new ApiException("VALIDATION_FAILED", "Upload a file up to 10 MB.", 400);
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        String type = file.getContentType() == null ? "" : file.getContentType();
        boolean allowed = ALLOWED.contains(type) || name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".pdf");
        if (!allowed) {
            throw new ApiException("VALIDATION_FAILED", "Only markdown, text, and PDF files are accepted.", 400);
        }
        byte[] bytes = file.getBytes();
        if (containsNul(bytes) && !name.endsWith(".pdf")) {
            throw new ApiException("VALIDATION_FAILED", "Text uploads must be UTF-8 documents.", 400);
        }
        Long bases = jdbc.queryForObject(
                "select count(*) from knowledge_bases where id = :id and workspace_id = :workspace",
                Map.of("id", id, "workspace", membership.workspaceId()), Long.class);
        if (bases == null || bases == 0) {
            throw new ApiException("NOT_FOUND", "Knowledge base not found.", 404);
        }
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        UUID documentId = UUID.randomUUID();
        String key = "documents/" + membership.workspaceId() + "/" + documentId;
        objects.put(key, bytes, type.isBlank() ? "application/octet-stream" : type);
        String media = name.endsWith(".pdf") ? "application/pdf" : name.endsWith(".md") ? "text/markdown" : "text/plain";
        jdbc.update("""
                insert into documents (id, workspace_id, knowledge_base_id, title, media_type, storage_key, checksum_sha256, status, created_by)
                values (:id, :workspace, :kb, :title, :media, :key, :checksum, 'UPLOADED', :user)
                """, new MapSqlParameterSource()
                .addValue("id", documentId)
                .addValue("workspace", membership.workspaceId())
                .addValue("kb", id)
                .addValue("title", title)
                .addValue("media", media)
                .addValue("key", key)
                .addValue("checksum", checksum)
                .addValue("user", rbac.current().id()));
        jdbc.update("""
                insert into jobs (id, workspace_id, job_type, payload, status, max_attempts, idempotency_key)
                values (:id, :workspace, 'EMBED_DOCUMENT', :payload, 'PENDING', 5, :idem)
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("workspace", membership.workspaceId())
                .addValue("payload", Jsons.jsonb(Jsons.write(mapper, Map.of("documentId", documentId.toString()))))
                .addValue("idem", "embed:" + documentId));
        audit.record(membership.workspaceId(), rbac.current().id(), "DOCUMENT_UPLOADED", "document", documentId.toString(), null,
                Map.of("title", title, "checksum", checksum));
        return org.springframework.http.ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id", documentId, "status", "UPLOADED"));
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) return true;
        }
        return false;
    }

    public record CreateBase(String name, String slug, String embeddingModel) {}
}
