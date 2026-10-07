package com.aegistrace.knowledge;

import com.aegistrace.audit.AuditService;
import com.aegistrace.common.ApiException;
import com.aegistrace.common.Jsons;
import com.aegistrace.runtime.RuntimeClient;
import com.aegistrace.security.Actor;
import com.aegistrace.storage.ObjectStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class KnowledgeService {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;
    public static final int DEFAULT_TOP_K = 5;
    public static final int MAX_TOP_K = 20;
    public static final int MAX_QUERY = 2000;
    public static final int MAX_CHUNKS = 50;
    public static final int MAX_FILTER_LENGTH = 200;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectStore objects;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final RuntimeClient runtime;

    public KnowledgeService(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, ObjectStore objects,
                            ObjectMapper mapper, AuditService audit, RuntimeClient runtime) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.objects = objects;
        this.mapper = mapper;
        this.audit = audit;
        this.runtime = runtime;
    }

    public List<Map<String, Object>> listBases(UUID workspaceId) {
        return jdbc.query("""
                select kb.id, kb.name, kb.slug, kb.embedding_model, kb.status,
                       kv.id as version_id, kv.version_number,
                       (select count(*) from documents d where d.knowledge_base_id = kb.id) as documents,
                       (select count(*) from documents d where d.knowledge_base_id = kb.id and d.status = 'ACTIVE') as ready,
                       (select count(*) from documents d where d.knowledge_base_id = kb.id and d.status in ('UPLOADED', 'PROCESSING')) as processing,
                       (select count(*) from documents d where d.knowledge_base_id = kb.id and d.status = 'FAILED') as failed
                from knowledge_bases kb
                left join knowledge_base_versions kv on kv.knowledge_base_id = kb.id and kv.current_version
                where kb.workspace_id = :workspace
                order by kb.name
                """, Map.of("workspace", workspaceId), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("name", rs.getString("name"));
            row.put("slug", rs.getString("slug"));
            row.put("embeddingModel", rs.getString("embedding_model"));
            row.put("status", rs.getString("status"));
            row.put("currentVersionId", rs.getString("version_id") == null ? null : UUID.fromString(rs.getString("version_id")));
            row.put("currentVersion", rs.getObject("version_number") == null ? null : rs.getInt("version_number"));
            row.put("documentCount", rs.getLong("documents"));
            row.put("readyCount", rs.getLong("ready"));
            row.put("processingCount", rs.getLong("processing"));
            row.put("failedCount", rs.getLong("failed"));
            return row;
        });
    }

    public Map<String, Object> createBase(Actor actor, UUID workspaceId, String name, String slug, String embeddingModel) {
        if (name == null || name.isBlank() || slug == null || slug.isBlank()) {
            throw new ApiException("VALIDATION_FAILED", "Name and slug are required.", 400);
        }
        UUID id = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String model = embeddingModel == null || embeddingModel.isBlank() ? "feature-hash-v1" : embeddingModel;
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into knowledge_bases (id, workspace_id, name, slug, embedding_model, embedding_dim)
                    values (:id, :workspace, :name, :slug, :model, 384)
                    """, Map.of("id", id, "workspace", workspaceId, "name", name.trim(), "slug", slug.trim(), "model", model));
            jdbc.update("""
                    insert into knowledge_base_versions (id, knowledge_base_id, version_number, current_version, created_by)
                    values (:id, :kb, 1, true, :user)
                    """, Map.of("id", versionId, "kb", id, "user", actor.id()));
        });
        audit.record(workspaceId, actor.id(), "KNOWLEDGE_BASE_CREATED", "knowledge_base", id.toString(), null, Map.of());
        return Map.of("id", id, "currentVersionId", versionId, "currentVersion", 1);
    }

    public Map<String, Object> documents(UUID workspaceId, UUID knowledgeBaseId, int page, int size, String status, String query, String mediaType) {
        ensureBase(workspaceId, knowledgeBaseId);
        if ((query != null && query.length() > MAX_FILTER_LENGTH)
                || (mediaType != null && mediaType.length() > MAX_FILTER_LENGTH)) {
            throw new ApiException("VALIDATION_ERROR", "Document filters are limited to 200 characters.", 400);
        }
        if (!blank(status) && !Set.of("UPLOADED", "PROCESSING", "ACTIVE", "FAILED", "DISABLED")
                .contains(status.trim().toUpperCase())) {
            throw new ApiException("VALIDATION_ERROR", "Unknown document status.", 400);
        }
        int pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? DEFAULT_PAGE_SIZE : size));
        int pageIndex = Math.max(0, page);
        var params = new MapSqlParameterSource()
                .addValue("id", knowledgeBaseId)
                .addValue("workspace", workspaceId)
                .addValue("status", blank(status) ? null : status.trim().toUpperCase())
                .addValue("q", blank(query) ? null : "%" + query.trim() + "%")
                .addValue("media", blank(mediaType) ? null : mediaType)
                .addValue("limit", pageSize)
                .addValue("offset", pageIndex * pageSize);
        Long total = jdbc.queryForObject("""
                select count(*) from documents d
                where d.knowledge_base_id = :id and d.workspace_id = :workspace
                  and (:status::text is null or d.status = :status)
                  and (:media::text is null or d.media_type = :media)
                  and (:q::text is null or d.title ilike :q)
                """, params, Long.class);
        var items = jdbc.query("""
                select d.id, d.title, d.media_type, d.status, d.error_message, d.created_at, d.checksum_sha256, d.byte_size,
                       d.storage_key,
                       (select count(*) from document_chunks c where c.document_id = d.id) as chunks,
                       (select count(*) from document_chunks c where c.document_id = d.id and c.embedding is not null) as embeddings
                from documents d
                where d.knowledge_base_id = :id and d.workspace_id = :workspace
                  and (:status::text is null or d.status = :status)
                  and (:media::text is null or d.media_type = :media)
                  and (:q::text is null or d.title ilike :q)
                order by d.created_at desc
                limit :limit offset :offset
                """, params, (rs, n) -> documentRow(rs, false));
        var body = new LinkedHashMap<String, Object>();
        body.put("items", items);
        body.put("total", total == null ? 0 : total);
        body.put("page", pageIndex);
        body.put("size", pageSize);
        return body;
    }

    public Map<String, Object> document(UUID workspaceId, UUID documentId) {
        var rows = jdbc.query("""
                select d.id, d.title, d.media_type, d.status, d.error_message, d.created_at, d.checksum_sha256, d.byte_size,
                       d.storage_key, d.knowledge_base_id, d.workspace_id, kb.name as knowledge_name, kb.embedding_model,
                       (select count(*) from document_chunks c where c.document_id = d.id) as chunks,
                       (select count(*) from document_chunks c where c.document_id = d.id and c.embedding is not null) as embeddings
                from documents d
                join knowledge_bases kb on kb.id = d.knowledge_base_id
                where d.id = :id
                """, Map.of("id", documentId), (rs, n) -> {
            KnowledgeAccess.assertWorkspace(workspaceId, UUID.fromString(rs.getString("workspace_id")));
            var row = documentRow(rs, true);
            row.put("knowledgeBaseId", UUID.fromString(rs.getString("knowledge_base_id")));
            row.put("knowledgeName", rs.getString("knowledge_name"));
            row.put("embeddingModel", rs.getString("embedding_model"));
            row.put("extractedText", extractedText(documentId, 20_000));
            return row;
        });
        if (rows.isEmpty()) {
            throw new ApiException("NOT_FOUND", "Document not found.", 404);
        }
        return rows.get(0);
    }

    public Map<String, Object> chunks(UUID workspaceId, UUID documentId, int page, int size) {
        document(workspaceId, documentId);
        int pageSize = Math.min(MAX_CHUNKS, Math.max(1, size <= 0 ? MAX_CHUNKS : size));
        int pageIndex = Math.max(0, page);
        var params = Map.of("id", documentId, "limit", pageSize, "offset", pageIndex * pageSize);
        Long total = jdbc.queryForObject("select count(*) from document_chunks where document_id = :id", Map.of("id", documentId), Long.class);
        var items = jdbc.query("""
                select id, chunk_index, section, page_number, content, embedding_model,
                       (embedding is not null) as embedded, length(content) as characters
                from document_chunks
                where document_id = :id
                order by chunk_index
                limit :limit offset :offset
                """, params, (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("sequence", rs.getInt("chunk_index"));
            row.put("section", rs.getString("section") == null ? "" : rs.getString("section"));
            row.put("page", rs.getObject("page_number") == null ? null : rs.getInt("page_number"));
            row.put("text", rs.getString("content"));
            row.put("embeddingModel", rs.getString("embedding_model"));
            row.put("embeddingStatus", rs.getBoolean("embedded") ? "EMBEDDED" : "NOT_EMBEDDED");
            row.put("characters", rs.getInt("characters"));
            return row;
        });
        var body = new LinkedHashMap<String, Object>();
        body.put("items", items);
        body.put("total", total == null ? 0 : total);
        body.put("page", pageIndex);
        body.put("size", pageSize);
        return body;
    }

    public Map<String, Object> upload(Actor actor, UUID workspaceId, UUID knowledgeBaseId, String title, String originalName,
                                      String declaredType, byte[] bytes) {
        ensureBase(workspaceId, knowledgeBaseId);
        var file = KnowledgeFiles.validate(originalName, declaredType, bytes);
        UUID documentId = UUID.randomUUID();
        String key = "documents/" + workspaceId + "/" + knowledgeBaseId + "/" + file.checksumSha256();
        objects.put(key, bytes, file.mediaType());
        String resolvedTitle = title == null || title.isBlank() ? fallbackTitle(originalName) : title.trim();
        tx.executeWithoutResult(status -> {
            jdbc.query("select pg_advisory_xact_lock(hashtextextended(:key, 0))",
                    Map.of("key", "document:" + knowledgeBaseId + ":" + file.checksumSha256()), rs -> null);
            Integer existing = jdbc.queryForObject("""
                    select count(*)::int from documents
                    where knowledge_base_id = :kb and checksum_sha256 = :checksum
                      and status in ('UPLOADED', 'PROCESSING', 'ACTIVE')
                    """, Map.of("kb", knowledgeBaseId, "checksum", file.checksumSha256()), Integer.class);
            if (existing != null && existing > 0) {
                throw new ApiException("DUPLICATE_DOCUMENT",
                        "A document with this checksum already exists in this knowledge base.", 409);
            }
            jdbc.update("""
                    insert into documents (
                        id, workspace_id, knowledge_base_id, title, media_type, storage_key, checksum_sha256, byte_size, status, created_by
                    ) values (:id, :workspace, :kb, :title, :media, :key, :checksum, :size, 'UPLOADED', :user)
                    """, new MapSqlParameterSource()
                    .addValue("id", documentId)
                    .addValue("workspace", workspaceId)
                    .addValue("kb", knowledgeBaseId)
                    .addValue("title", resolvedTitle)
                    .addValue("media", file.mediaType())
                    .addValue("key", key)
                    .addValue("checksum", file.checksumSha256())
                    .addValue("size", file.byteSize())
                    .addValue("user", actor.id()));
            jdbc.update("""
                    insert into jobs (id, workspace_id, job_type, payload, status, max_attempts, idempotency_key)
                    values (:id, :workspace, 'EMBED_DOCUMENT', :payload, 'PENDING', 5, :idem)
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("workspace", workspaceId)
                    .addValue("payload", Jsons.jsonb(Jsons.write(mapper, Map.of("documentId", documentId.toString()))))
                    .addValue("idem", "embed:" + documentId));
            audit.record(workspaceId, actor.id(), "DOCUMENT_UPLOADED", "document", documentId.toString(), null,
                    Map.of("title", resolvedTitle, "checksum", file.checksumSha256()));
        });
        var body = new LinkedHashMap<String, Object>();
        body.put("id", documentId);
        body.put("status", "UPLOADED");
        body.put("checksumSha256", file.checksumSha256());
        return body;
    }

    public List<Map<String, Object>> versions(UUID workspaceId, UUID knowledgeBaseId) {
        ensureBase(workspaceId, knowledgeBaseId);
        return jdbc.query("""
                select kv.id, kv.version_number, kv.current_version, kv.created_at,
                       (select count(*) from knowledge_version_documents kvd where kvd.knowledge_base_version_id = kv.id) as documents,
                       (select count(*) from knowledge_version_documents kvd
                          join documents d on d.id = kvd.document_id
                          where kvd.knowledge_base_version_id = kv.id and d.status = 'ACTIVE') as ready
                from knowledge_base_versions kv
                where kv.knowledge_base_id = :kb
                order by kv.version_number desc
                """, Map.of("kb", knowledgeBaseId), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("version", rs.getInt("version_number"));
            row.put("current", rs.getBoolean("current_version"));
            row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            row.put("documentCount", rs.getLong("documents"));
            row.put("readyCount", rs.getLong("ready"));
            return row;
        });
    }

    public Map<String, Object> publish(Actor actor, UUID workspaceId, UUID knowledgeBaseId) {
        ensureBase(workspaceId, knowledgeBaseId);
        return tx.execute(status -> {
            jdbc.queryForObject("select id from knowledge_bases where id = :id for update", Map.of("id", knowledgeBaseId), String.class);
            Integer incomplete = jdbc.queryForObject("""
                    select count(*)::int from documents
                    where knowledge_base_id = :kb and workspace_id = :workspace
                      and status in ('UPLOADED', 'PROCESSING', 'FAILED')
                    """, Map.of("kb", knowledgeBaseId, "workspace", workspaceId), Integer.class);
            if (incomplete != null && incomplete > 0) {
                throw new ApiException("VALIDATION_FAILED", "Every document must be ACTIVE before a knowledge version can be published.", 409);
            }
            Integer ready = jdbc.queryForObject("""
                    select count(*)::int from documents d
                    where d.knowledge_base_id = :kb and d.workspace_id = :workspace and d.status = 'ACTIVE'
                      and exists (select 1 from document_chunks c where c.document_id = d.id and c.embedding is not null)
                    """, Map.of("kb", knowledgeBaseId, "workspace", workspaceId), Integer.class);
            if (ready == null || ready == 0) {
                throw new ApiException("VALIDATION_FAILED", "A knowledge version needs at least one embedded ACTIVE document.", 409);
            }
            Integer next = jdbc.queryForObject(
                    "select coalesce(max(version_number), 0) + 1 from knowledge_base_versions where knowledge_base_id = :kb",
                    Map.of("kb", knowledgeBaseId), Integer.class);
            UUID versionId = UUID.randomUUID();
            jdbc.update("""
                    insert into knowledge_base_versions (id, knowledge_base_id, version_number, current_version, created_by)
                    values (:id, :kb, :version, false, :user)
                    """, Map.of("id", versionId, "kb", knowledgeBaseId, "version", next, "user", actor.id()));
            jdbc.update("""
                    insert into knowledge_version_documents (knowledge_base_version_id, document_id)
                    select :version, d.id from documents d
                    where d.knowledge_base_id = :kb and d.workspace_id = :workspace and d.status = 'ACTIVE'
                      and exists (select 1 from document_chunks c where c.document_id = d.id and c.embedding is not null)
                    """, Map.of("version", versionId, "kb", knowledgeBaseId, "workspace", workspaceId));
            audit.record(workspaceId, actor.id(), "KNOWLEDGE_VERSION_CREATED", "knowledge_base_version", versionId.toString(), null,
                    Map.of("version", next));
            return Map.of("id", versionId, "version", next, "current", false, "documentCount", ready);
        });
    }

    public Map<String, Object> activate(Actor actor, UUID workspaceId, UUID knowledgeBaseId, UUID versionId) {
        ensureBase(workspaceId, knowledgeBaseId);
        return tx.execute(status -> {
            jdbc.queryForObject("select id from knowledge_bases where id = :id for update", Map.of("id", knowledgeBaseId), String.class);
            Integer found = jdbc.queryForObject("""
                    select count(*)::int from knowledge_base_versions
                    where id = :id and knowledge_base_id = :kb
                    """, Map.of("id", versionId, "kb", knowledgeBaseId), Integer.class);
            if (found == null || found == 0) {
                throw new ApiException("NOT_FOUND", "Knowledge version not found.", 404);
            }
            Integer broken = jdbc.queryForObject("""
                    select count(*)::int from knowledge_version_documents kvd
                    join documents d on d.id = kvd.document_id
                    where kvd.knowledge_base_version_id = :id
                      and (d.status <> 'ACTIVE'
                           or not exists (select 1 from document_chunks c where c.document_id = d.id and c.embedding is not null))
                    """, Map.of("id", versionId), Integer.class);
            if (broken != null && broken > 0) {
                throw new ApiException("VALIDATION_FAILED", "This knowledge version still has incomplete documents.", 409);
            }
            jdbc.update("update knowledge_base_versions set current_version = false where knowledge_base_id = :kb",
                    Map.of("kb", knowledgeBaseId));
            jdbc.update("update knowledge_base_versions set current_version = true where id = :id", Map.of("id", versionId));
            Integer number = jdbc.queryForObject("select version_number from knowledge_base_versions where id = :id",
                    Map.of("id", versionId), Integer.class);
            audit.record(workspaceId, actor.id(), "KNOWLEDGE_VERSION_ACTIVATED", "knowledge_base_version", versionId.toString(), null,
                    Map.of("version", number));
            return Map.of("id", versionId, "version", number, "current", true);
        });
    }

    public Map<String, Object> retrieve(UUID workspaceId, UUID knowledgeBaseId, String query, String versionId, Integer topK) {
        var base = ensureBase(workspaceId, knowledgeBaseId);
        if (query == null || query.isBlank()) {
            throw new ApiException("VALIDATION_FAILED", "A retrieval query is required.", 400);
        }
        if (query.length() > MAX_QUERY) {
            throw new ApiException("VALIDATION_FAILED", "Queries are limited to 2000 characters.", 400);
        }
        int k = topK == null ? DEFAULT_TOP_K : topK;
        if (k < 1 || k > MAX_TOP_K) {
            throw new ApiException("VALIDATION_FAILED", "topK must be between 1 and 20.", 400);
        }
        UUID resolvedVersion = resolveVersion(workspaceId, knowledgeBaseId, versionId);
        Integer versionNumber = jdbc.queryForObject(
                "select version_number from knowledge_base_versions where id = :id",
                Map.of("id", resolvedVersion), Integer.class);
        var started = System.nanoTime();
        var hits = runtime.retrieve(Map.of(
                "workspaceId", workspaceId.toString(),
                "knowledgeBaseId", knowledgeBaseId.toString(),
                "knowledgeBaseVersionId", resolvedVersion.toString(),
                "embeddingModel", base.get("embeddingModel"),
                "query", query.trim(),
                "topK", k
        ));
        long latencyMs = (System.nanoTime() - started) / 1_000_000L;
        var body = new LinkedHashMap<String, Object>();
        body.put("query", query.trim());
        body.put("knowledgeBaseId", knowledgeBaseId);
        body.put("knowledgeBaseName", base.get("name"));
        body.put("knowledgeBaseVersionId", resolvedVersion);
        body.put("knowledgeVersion", versionNumber);
        body.put("topK", k);
        body.put("similaritySemantics", "Higher fusion, vector cosine, and lexical scores are better. Scores are not percentages.");
        body.put("latencyMs", latencyMs);
        body.put("hits", hits);
        return body;
    }

    public Map<String, Object> resolveKnowledgeVersion(UUID workspaceId, String knowledgeBaseId, String knowledgeBaseVersionId) {
        UUID kb = UUID.fromString(knowledgeBaseId);
        UUID version = resolveVersion(workspaceId, kb, knowledgeBaseVersionId);
        Integer number = jdbc.queryForObject(
                "select version_number from knowledge_base_versions where id = :id", Map.of("id", version), Integer.class);
        String model = jdbc.queryForObject(
                "select embedding_model from knowledge_bases where id = :id and workspace_id = :workspace",
                Map.of("id", kb, "workspace", workspaceId), String.class);
        return Map.of("knowledgeBaseId", kb, "knowledgeBaseVersionId", version, "knowledgeVersion", number, "embeddingModel", model);
    }

    private UUID resolveVersion(UUID workspaceId, UUID knowledgeBaseId, String knowledgeBaseVersionId) {
        if (knowledgeBaseVersionId != null && !knowledgeBaseVersionId.isBlank()) {
            UUID id = UUID.fromString(knowledgeBaseVersionId);
            Integer found = jdbc.queryForObject("""
                    select count(*)::int from knowledge_base_versions kv
                    join knowledge_bases kb on kb.id = kv.knowledge_base_id
                    where kv.id = :id and kv.knowledge_base_id = :kb and kb.workspace_id = :workspace
                    """, Map.of("id", id, "kb", knowledgeBaseId, "workspace", workspaceId), Integer.class);
            if (found == null || found == 0) {
                throw new ApiException("NOT_FOUND", "Knowledge version not found.", 404);
            }
            return id;
        }
        var current = jdbc.query("""
                select kv.id from knowledge_base_versions kv
                join knowledge_bases kb on kb.id = kv.knowledge_base_id
                where kv.knowledge_base_id = :kb and kv.current_version and kb.workspace_id = :workspace
                """, Map.of("kb", knowledgeBaseId, "workspace", workspaceId),
                (rs, n) -> UUID.fromString(rs.getString("id")));
        if (current.isEmpty()) {
            throw new ApiException("NOT_FOUND", "No current knowledge version exists.", 404);
        }
        return current.get(0);
    }

    private Map<String, Object> ensureBase(UUID workspaceId, UUID knowledgeBaseId) {
        var rows = jdbc.query("""
                select id, name, embedding_model, workspace_id from knowledge_bases where id = :id
                """, Map.of("id", knowledgeBaseId), (rs, n) -> {
            KnowledgeAccess.assertWorkspace(workspaceId, UUID.fromString(rs.getString("workspace_id")));
            return Map.<String, Object>of(
                    "id", UUID.fromString(rs.getString("id")),
                    "name", rs.getString("name"),
                    "embeddingModel", rs.getString("embedding_model"));
        });
        if (rows.isEmpty()) {
            throw new ApiException("NOT_FOUND", "Knowledge base not found.", 404);
        }
        return rows.get(0);
    }

    private Map<String, Object> documentRow(java.sql.ResultSet rs, boolean includeChecksum) throws java.sql.SQLException {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", UUID.fromString(rs.getString("id")));
        row.put("title", rs.getString("title"));
        row.put("mediaType", rs.getString("media_type"));
        row.put("status", rs.getString("status"));
        row.put("errorMessage", rs.getString("error_message"));
        row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
        row.put("byteSize", rs.getLong("byte_size"));
        row.put("chunks", rs.getLong("chunks"));
        row.put("embeddings", rs.getLong("embeddings"));
        row.put("embeddingStatus", embeddingStatus(rs.getString("status"), rs.getLong("chunks"), rs.getLong("embeddings")));
        row.put("testArtifact", testArtifact(rs.getString("storage_key"), rs.getString("title")));
        if (includeChecksum) {
            row.put("checksumSha256", rs.getString("checksum_sha256"));
        }
        return row;
    }

    static String embeddingStatus(String status, long chunks, long embeddings) {
        if ("FAILED".equals(status)) return "FAILED";
        if ("UPLOADED".equals(status)) return "NOT_EMBEDDED";
        if ("PROCESSING".equals(status)) return "EMBEDDING";
        if ("ACTIVE".equals(status) && chunks > 0 && embeddings == chunks) return "EMBEDDED";
        if (chunks == 0) return "NOT_EMBEDDED";
        return "EMBEDDING";
    }

    static String testArtifact(String storageKey, String title) {
        String key = storageKey == null ? "" : storageKey;
        String name = title == null ? "" : title;
        if (key.endsWith("11-malicious-override.md") || name.toLowerCase().contains("internal override")) {
            return "PROMPT_INJECTION_FIXTURE";
        }
        return null;
    }

    private String extractedText(UUID documentId, int limit) {
        var parts = jdbc.query("""
                select content from document_chunks where document_id = :id order by chunk_index limit 40
                """, Map.of("id", documentId), (rs, n) -> rs.getString("content"));
        if (parts.isEmpty()) return "";
        String joined = String.join("\n\n", parts);
        return joined.length() <= limit ? joined : joined.substring(0, limit);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String fallbackTitle(String originalName) {
        if (originalName == null || originalName.isBlank()) return "Untitled document";
        int slash = Math.max(originalName.lastIndexOf('/'), originalName.lastIndexOf('\\'));
        return originalName.substring(slash + 1);
    }
}
