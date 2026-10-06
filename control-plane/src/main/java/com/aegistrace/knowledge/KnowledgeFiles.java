package com.aegistrace.knowledge;

import com.aegistrace.common.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class KnowledgeFiles {
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    private KnowledgeFiles() {}

    public static ValidatedFile validate(String originalName, String declaredType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiException("VALIDATION_FAILED", "Empty files cannot be ingested.", 400);
        }
        if (bytes.length > MAX_BYTES) {
            throw new ApiException("VALIDATION_FAILED", "Upload a file up to 10 MB.", 400);
        }
        String name = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        String declared = declaredType == null ? "" : declaredType.toLowerCase(Locale.ROOT);
        boolean pdfName = name.endsWith(".pdf") || declared.contains("pdf");
        boolean markdownName = name.endsWith(".md") || name.endsWith(".markdown") || declared.contains("markdown");
        boolean textName = name.endsWith(".txt") || declared.equals("text/plain");
        boolean looksPdf = bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
        if (pdfName || looksPdf) {
            if (!looksPdf) {
                throw new ApiException("VALIDATION_FAILED", "PDF uploads must start with a PDF header.", 400);
            }
            return new ValidatedFile("application/pdf", sha256(bytes), bytes.length);
        }
        if (!(markdownName || textName || declared.equals("text/plain") || declared.equals("text/markdown") || declared.equals("text/x-markdown"))) {
            throw new ApiException("VALIDATION_FAILED", "Only markdown, text, and PDF files are accepted.", 400);
        }
        if (containsNul(bytes)) {
            throw new ApiException("VALIDATION_FAILED", "Text uploads must be UTF-8 documents.", 400);
        }
        String text = new String(bytes, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            throw new ApiException("VALIDATION_FAILED", "Empty files cannot be ingested.", 400);
        }
        String media = markdownName || declared.contains("markdown") ? "text/markdown" : "text/plain";
        return new ValidatedFile(media, sha256(bytes), bytes.length);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) return true;
        }
        return false;
    }

    public record ValidatedFile(String mediaType, String checksumSha256, long byteSize) {}
}
