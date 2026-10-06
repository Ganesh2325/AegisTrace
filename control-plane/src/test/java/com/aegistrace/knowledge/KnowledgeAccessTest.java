package com.aegistrace.knowledge;

import com.aegistrace.common.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeAccessTest {
    @Test
    void operatorCanReadAndCannotManage() {
        assertTrue(KnowledgeAccess.canRead("OPERATOR"));
        assertFalse(KnowledgeAccess.canManage("OPERATOR"));
        assertFalse(KnowledgeAccess.canRead("REVIEWER"));
        assertTrue(KnowledgeAccess.canManage("DEVELOPER"));
        assertTrue(KnowledgeAccess.canManage("ADMIN"));
    }

    @Test
    void foreignWorkspaceLooksMissing() {
        ApiException ex = assertThrows(ApiException.class, () ->
                KnowledgeAccess.assertWorkspace(UUID.randomUUID(), UUID.randomUUID()));
        assertEquals(404, ex.getStatus());
        assertEquals("NOT_FOUND", ex.getCode());
    }

    @Test
    void validatesMarkdownPdfAndRejectsEmptyOrUnsupported() {
        var md = KnowledgeFiles.validate("policy.md", "text/markdown", "# Refunds\nRefunds take 5 days.".getBytes(StandardCharsets.UTF_8));
        assertEquals("text/markdown", md.mediaType());
        ApiException empty = assertThrows(ApiException.class, () ->
                KnowledgeFiles.validate("empty.txt", "text/plain", new byte[0]));
        assertEquals(400, empty.getStatus());
        ApiException type = assertThrows(ApiException.class, () ->
                KnowledgeFiles.validate("notes.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "hello".getBytes()));
        assertEquals(400, type.getStatus());
        byte[] pdf = "%PDF-1.4 fake".getBytes(StandardCharsets.US_ASCII);
        assertEquals("application/pdf", KnowledgeFiles.validate("file.pdf", "application/pdf", pdf).mediaType());
        ApiException fakePdf = assertThrows(ApiException.class, () ->
                KnowledgeFiles.validate("file.pdf", "application/pdf", "not a pdf".getBytes()));
        assertEquals(400, fakePdf.getStatus());
    }

    @Test
    void embeddingStatusAndInjectionFixtureLabels() {
        assertEquals("EMBEDDED", KnowledgeService.embeddingStatus("ACTIVE", 4, 4));
        assertEquals("EMBEDDING", KnowledgeService.embeddingStatus("PROCESSING", 0, 0));
        assertEquals("FAILED", KnowledgeService.embeddingStatus("FAILED", 0, 0));
        assertEquals("PROMPT_INJECTION_FIXTURE", KnowledgeService.testArtifact("seed://11-malicious-override.md", "Internal override"));
        assertEquals(null, KnowledgeService.testArtifact("documents/ws/doc", "Refund policy"));
    }
}
