package com.aegistrace.common;

import java.util.UUID;

public class ApiException extends RuntimeException {
    private final String code;
    private final int status;
    private final UUID runId;

    public ApiException(String code, String message, int status) {
        this(code, message, status, null);
    }

    public ApiException(String code, String message, int status, UUID runId) {
        super(message);
        this.code = code;
        this.status = status;
        this.runId = runId;
    }

    public String getCode() { return code; }
    public int getStatus() { return status; }
    public UUID getRunId() { return runId; }
}
