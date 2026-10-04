package com.aegistrace.policy;

import java.util.Map;
import java.util.Set;

public final class ArgumentValidator {
    private static final Set<String> SEARCH_KEYS = Set.of("query", "topK");
    private static final Set<String> TICKET_KEYS = Set.of("subject", "description", "priority", "category");
    private static final Set<String> KNOWN_PRIORITIES = Set.of("low", "normal", "high", "urgent", "critical");

    private ArgumentValidator() {}

    public static Result validate(String toolName, Map<String, Object> arguments) {
        if (arguments == null) {
            return Result.invalid("Arguments are required.");
        }
        return switch (toolName) {
            case "search_knowledge" -> search(arguments);
            case "create_support_ticket" -> ticket(arguments);
            default -> Result.ok();
        };
    }

    private static Result search(Map<String, Object> arguments) {
        for (String key : arguments.keySet()) {
            if (!SEARCH_KEYS.contains(key)) {
                return Result.invalid("Unknown argument: " + key);
            }
        }
        Object query = arguments.get("query");
        if (!(query instanceof String text) || text.isBlank() || text.length() > 2000) {
            return Result.invalid("query must be a non-empty string.");
        }
        if (arguments.containsKey("topK") && !(arguments.get("topK") instanceof Number number && number.intValue() >= 1 && number.intValue() <= 20)) {
            return Result.invalid("topK must be between 1 and 20.");
        }
        return Result.ok();
    }

    private static Result ticket(Map<String, Object> arguments) {
        for (String key : arguments.keySet()) {
            if (!TICKET_KEYS.contains(key)) {
                return Result.invalid("Unknown argument: " + key);
            }
        }
        for (String required : TICKET_KEYS) {
            Object value = arguments.get(required);
            if (!(value instanceof String text) || text.isBlank()) {
                return Result.invalid(required + " is required.");
            }
        }
        String subject = (String) arguments.get("subject");
        String description = (String) arguments.get("description");
        String priority = (String) arguments.get("priority");
        String category = (String) arguments.get("category");
        if (subject.length() > 200 || description.length() > 4000 || category.length() > 80) {
            return Result.invalid("An argument exceeds its maximum length.");
        }
        if (!KNOWN_PRIORITIES.contains(priority)) {
            return Result.invalid("Unknown priority.");
        }
        return Result.ok();
    }

    public record Result(boolean valid, String error) {
        public static Result ok() { return new Result(true, null); }
        public static Result invalid(String error) { return new Result(false, error); }
    }
}
