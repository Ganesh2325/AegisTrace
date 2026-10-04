package com.aegistrace.common;

import java.time.Instant;
import java.util.UUID;

public record ApiError(String code, String message, String traceId, UUID runId, Instant timestamp) {
}
