package com.aegistrace.metrics;

import com.aegistrace.run.RunService;
import com.aegistrace.run.RunStateMachine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns already workspace-filtered rows into the operations summary.
 * Percentiles use the PostgreSQL {@code percentile_cont} linear interpolation
 * so a unit test and the database agree. Verified against Postgres 16 on
 * 1000..10000: p50 = 5500, p95 ≈ 9550, p99 = 9910.
 */
public final class MetricsCalculator {
    /** Operator attention line for end-to-end p95. Not an SLO. */
    public static final long LATENCY_ATTENTION_MS = 300_000L;

    private MetricsCalculator() {}

    public record Window(String code, Integer hours, Instant start, Instant end) {
        public static Window parse(String raw, Instant now) {
            Instant close = now == null ? Instant.now() : now;
            if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw) || "ALL_TIME".equalsIgnoreCase(raw)) {
                return allTimeAt(close);
            }
            String normalized = raw.trim().toUpperCase();
            return switch (normalized) {
                case "1H", "1HR" -> rollingHours(1, close);
                case "24H", "1D" -> rollingHours(24, close);
                case "7D", "168H" -> rollingHours(168, close);
                case "30D", "720H" -> rollingHours(720, close);
                default -> throw new IllegalArgumentException("Unsupported window");
            };
        }

        public static Window allTimeAt(Instant end) {
            Instant close = end == null ? Instant.now() : end;
            return new Window("ALL_TIME", null, null, close);
        }

        public static Window allTime() {
            return allTimeAt(Instant.now());
        }

        public static Window rollingHours(int hours, Instant end) {
            Instant close = end == null ? Instant.now() : end;
            String code = hours == 24 ? "24H" : hours == 168 ? "7D" : hours == 720 ? "30D" : hours + "H";
            return new Window(code, hours, close.minusSeconds(hours * 3600L), close);
        }

        public boolean unbounded() {
            return hours == null;
        }

        public String sqlPredicate(String createdColumn) {
            if (unbounded()) {
                return "";
            }
            return " and " + createdColumn + " >= :windowStart and " + createdColumn + " < :windowEnd ";
        }
    }

    public record RunSample(String state, Double durationMs, long inputTokens, long outputTokens,
                            BigDecimal estimatedCostUsd, String model) {}

    public record ApprovalSample(String status, Double waitMs) {}

    public record RunAggregate(long runs, long active, long waiting, long terminal, long completed, long failed,
                               long timedOut, long cancelled, long tokens, BigDecimal cost, long validDurations,
                               long invalidDurations, Double p50, Double p95, Double p99,
                               java.util.Set<String> models) {}

    public record WaitAggregate(long samples, long invalid, Double meanMs) {}

    public static Map<String, Object> summarize(List<RunSample> runs, List<ApprovalSample> approvals,
                                                long openJobs, long policyDenials, long policyDecisions,
                                                long evaluations, long evaluationPasses) {
        return summarize(runs, approvals, openJobs, policyDenials, policyDecisions, evaluations, evaluationPasses, Window.allTime());
    }

    public static Map<String, Object> summarize(List<RunSample> runs, List<ApprovalSample> approvals,
                                                long openJobs, long policyDenials, long policyDecisions,
                                                long evaluations, long evaluationPasses, Window window) {
        Window resolved = window == null ? Window.allTime() : window;
        long completed = 0;
        long failed = 0;
        long timedOut = 0;
        long cancelled = 0;
        long terminal = 0;
        long active = 0;
        long waiting = 0;
        long tokens = 0;
        long invalidDurations = 0;
        BigDecimal cost = BigDecimal.ZERO;
        var durations = new ArrayList<Double>();
        var models = new java.util.LinkedHashSet<String>();
        for (RunSample run : runs) {
            tokens += run.inputTokens() + run.outputTokens();
            if (run.estimatedCostUsd() != null) {
                cost = cost.add(run.estimatedCostUsd());
            }
            models.add(run.model());
            if (RunStateMachine.isTerminal(run.state())) {
                terminal++;
                if ("COMPLETED".equals(run.state())) completed++;
                else if ("FAILED".equals(run.state())) failed++;
                else if ("TIMED_OUT".equals(run.state())) timedOut++;
                else if ("CANCELLED".equals(run.state())) cancelled++;
                if (run.durationMs() == null || run.durationMs() < 0) {
                    invalidDurations++;
                } else {
                    durations.add(run.durationMs());
                }
            } else if (run.durationMs() != null && run.durationMs() < 0) {
                invalidDurations++;
            } else if (RunStateMachine.isWaiting(run.state())) {
                waiting++;
            } else if (RunStateMachine.isActive(run.state())) {
                active++;
            }
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("window", resolved.code());
        body.put("windowHours", resolved.hours());
        body.put("timezone", "UTC");
        body.put("windowStart", resolved.start() == null ? null : resolved.start().toString());
        body.put("windowEnd", resolved.end().toString());
        body.put("runs", count(runs.size(), "Runs stored in this workspace, any state."));
        body.put("inProgress", count(active + waiting, "Active runs plus runs waiting for approval."));
        body.put("terminalRuns", count(terminal, "COMPLETED, FAILED, CANCELLED, and TIMED_OUT."));
        body.put("completed", count(completed, "Terminal runs in COMPLETED."));
        body.put("failed", count(failed, "Terminal runs in FAILED. Timeouts are separate."));
        body.put("timedOut", count(timedOut, "Terminal runs in TIMED_OUT."));
        body.put("cancelled", count(cancelled, "Terminal runs in CANCELLED. Not counted as failures."));
        body.put("completion", rate(completed, terminal, "COMPLETED / terminal runs"));
        body.put("failure", rate(failed + timedOut, terminal, "(FAILED + TIMED_OUT) / terminal runs"));
        body.put("latency", latency(durations, invalidDurations));
        body.put("approvalWait", approvalWait(approvals));
        body.put("pendingApprovals", count(countStatus(approvals, "PENDING"), "Approvals still PENDING in this workspace."));
        body.put("policyDenial", policy(policyDenials, policyDecisions));
        body.put("tokens", tokens(tokens, runs.size()));
        body.put("cost", cost(runs.size(), tokens, cost, models));
        body.put("queueDepth", queue(openJobs));
        body.put("evaluations", evaluations(evaluations, evaluationPasses));
        return body;
    }

    public static Map<String, Object> summarizeAggregates(
            RunAggregate runs, Map<String, WaitAggregate> waits, long pendingApprovals,
            long openJobs, long policyDenials, long policyDecisions,
            long evaluations, long evaluationPasses, Window window) {
        Window resolved = window == null ? Window.allTime() : window;
        var body = new LinkedHashMap<String, Object>();
        body.put("window", resolved.code());
        body.put("windowHours", resolved.hours());
        body.put("timezone", "UTC");
        body.put("windowStart", resolved.start() == null ? null : resolved.start().toString());
        body.put("windowEnd", resolved.end().toString());
        body.put("runs", count(runs.runs(), "Runs stored in this workspace, any state."));
        body.put("inProgress", count(runs.active() + runs.waiting(), "Active runs plus runs waiting for approval."));
        body.put("terminalRuns", count(runs.terminal(), "COMPLETED, FAILED, CANCELLED, and TIMED_OUT."));
        body.put("completed", count(runs.completed(), "Terminal runs in COMPLETED."));
        body.put("failed", count(runs.failed(), "Terminal runs in FAILED. Timeouts are separate."));
        body.put("timedOut", count(runs.timedOut(), "Terminal runs in TIMED_OUT."));
        body.put("cancelled", count(runs.cancelled(), "Terminal runs in CANCELLED. Not counted as failures."));
        body.put("completion", rate(runs.completed(), runs.terminal(), "COMPLETED / terminal runs"));
        body.put("failure", rate(runs.failed() + runs.timedOut(), runs.terminal(), "(FAILED + TIMED_OUT) / terminal runs"));
        body.put("latency", latencyAggregate(runs));
        body.put("approvalWait", approvalWaitAggregate(waits));
        body.put("pendingApprovals", count(pendingApprovals, "Approvals still PENDING in this workspace."));
        body.put("policyDenial", policy(policyDenials, policyDecisions));
        body.put("tokens", tokens(runs.tokens(), Math.toIntExact(Math.min(Integer.MAX_VALUE, runs.runs()))));
        body.put("cost", cost(Math.toIntExact(Math.min(Integer.MAX_VALUE, runs.runs())),
                runs.tokens(), runs.cost(), runs.models()));
        body.put("queueDepth", queue(openJobs));
        body.put("evaluations", evaluations(evaluations, evaluationPasses));
        return body;
    }

    /**
     * PostgreSQL {@code percentile_cont}: position {@code 1 + p * (n - 1)} on the
     * ordered sample, with linear interpolation between neighbors. One-based.
     */
    public static Double percentileCont(List<Double> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        var sorted = values.stream().sorted().toList();
        int n = sorted.size();
        if (n == 1) {
            return sorted.get(0);
        }
        double rank = 1 + percentile * (n - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        double low = sorted.get(lower - 1);
        double high = sorted.get(upper - 1);
        if (lower == upper) {
            return low;
        }
        return low + (rank - lower) * (high - low);
    }

    private static Map<String, Object> latency(List<Double> durations, long invalidDurations) {
        Double p50 = percentileCont(durations, 0.50);
        Double p95 = percentileCont(durations, 0.95);
        Double p99 = percentileCont(durations, 0.99);
        var body = new LinkedHashMap<String, Object>();
        body.put("status", durations.isEmpty() ? "NO_DATA" : "OK");
        body.put("definition", "percentile_cont of ended_at - started_at for terminal runs with a valid duration. "
                + "End-to-end wall-clock for this workspace in the selected window, including human approval wait. "
                + "When the window is ALL_TIME the sample is every valid terminal duration. "
                + "Agent execution time is not stored separately, so it is not reported.");
        body.put("unit", "ms");
        body.put("sampleCount", durations.size());
        body.put("invalidDurationCount", invalidDurations);
        body.put("p50Ms", p50);
        body.put("p95Ms", p95);
        body.put("p99Ms", p99);
        body.put("attentionMs", LATENCY_ATTENTION_MS);
        body.put("high", p95 != null && p95 >= LATENCY_ATTENTION_MS);
        return body;
    }

    private static Map<String, Object> latencyAggregate(RunAggregate runs) {
        var body = new LinkedHashMap<String, Object>();
        body.put("status", runs.validDurations() == 0 ? "NO_DATA" : "OK");
        body.put("definition", "percentile_cont of ended_at - started_at for terminal runs with a valid duration. "
                + "End-to-end wall-clock for this workspace in the selected window, including human approval wait. "
                + "When the window is ALL_TIME the sample is every valid terminal duration. "
                + "Agent execution time is not stored separately, so it is not reported.");
        body.put("unit", "ms");
        body.put("sampleCount", runs.validDurations());
        body.put("invalidDurationCount", runs.invalidDurations());
        body.put("p50Ms", runs.p50());
        body.put("p95Ms", runs.p95());
        body.put("p99Ms", runs.p99());
        body.put("attentionMs", LATENCY_ATTENTION_MS);
        body.put("high", runs.p95() != null && runs.p95() >= LATENCY_ATTENTION_MS);
        return body;
    }

    private static Map<String, Object> approvalWait(List<ApprovalSample> approvals) {
        var approved = waits(approvals, "APPROVED");
        var rejected = waits(approvals, "REJECTED");
        var expired = waits(approvals, "EXPIRED");
        var body = new LinkedHashMap<String, Object>();
        body.put("primary", "approved");
        body.put("definition", "Mean of decided_at - requested_at for APPROVED approvals in this workspace. "
                + "PENDING and CANCELLED are excluded. REJECTED and EXPIRED are reported separately and are not averaged in.");
        body.put("approved", approved);
        body.put("rejected", rejected);
        body.put("expired", expired);
        return body;
    }

    private static Map<String, Object> approvalWaitAggregate(Map<String, WaitAggregate> waits) {
        var body = new LinkedHashMap<String, Object>();
        body.put("primary", "approved");
        body.put("definition", "Mean of decided_at - requested_at for APPROVED approvals in this workspace. "
                + "PENDING and CANCELLED are excluded. REJECTED and EXPIRED are reported separately and are not averaged in.");
        body.put("approved", waitMap(waits.get("APPROVED")));
        body.put("rejected", waitMap(waits.get("REJECTED")));
        body.put("expired", waitMap(waits.get("EXPIRED")));
        return body;
    }

    private static Map<String, Object> waitMap(WaitAggregate value) {
        WaitAggregate wait = value == null ? new WaitAggregate(0, 0, null) : value;
        var body = new LinkedHashMap<String, Object>();
        body.put("status", wait.samples() == 0 ? "NO_DATA" : "OK");
        body.put("valueMs", wait.samples() == 0 ? null : wait.meanMs());
        body.put("sampleCount", wait.samples());
        body.put("invalidCount", wait.invalid());
        return body;
    }

    private static Map<String, Object> waits(List<ApprovalSample> approvals, String status) {
        double sum = 0;
        int samples = 0;
        int invalid = 0;
        for (ApprovalSample approval : approvals) {
            if (!status.equals(approval.status())) {
                continue;
            }
            if (approval.waitMs() == null || approval.waitMs() < 0) {
                invalid++;
                continue;
            }
            sum += approval.waitMs();
            samples++;
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("status", samples == 0 ? "NO_DATA" : "OK");
        body.put("valueMs", samples == 0 ? null : sum / samples);
        body.put("sampleCount", samples);
        body.put("invalidCount", invalid);
        return body;
    }

    private static long countStatus(List<ApprovalSample> approvals, String status) {
        return approvals.stream().filter(a -> status.equals(a.status())).count();
    }

    private static Map<String, Object> rate(long numerator, long terminal, String definition) {
        var body = new LinkedHashMap<String, Object>();
        body.put("status", terminal == 0 ? "NO_DATA" : "OK");
        body.put("value", terminal == 0 ? null : (double) numerator / terminal);
        body.put("definition", definition);
        body.put("numerator", numerator);
        body.put("terminal", terminal);
        return body;
    }

    private static Map<String, Object> count(long value, String definition) {
        var body = new LinkedHashMap<String, Object>();
        body.put("value", value);
        body.put("status", "OK");
        body.put("definition", definition);
        return body;
    }

    private static Map<String, Object> policy(long denials, long decisions) {
        var body = new LinkedHashMap<String, Object>();
        body.put("status", decisions == 0 ? "NO_DATA" : "OK");
        body.put("value", decisions == 0 ? null : (double) denials / decisions);
        body.put("denials", denials);
        body.put("decisions", decisions);
        body.put("definition", "DENY proposals / proposals with a policy decision, in this workspace.");
        return body;
    }

    private static Map<String, Object> tokens(long tokens, int runs) {
        var body = new LinkedHashMap<String, Object>();
        body.put("value", tokens);
        body.put("status", "OK");
        body.put("definition", runs == 0
                ? "No runs, so no recorded tokens. Zero here means an empty sum, not an unknown model report."
                : "Sum of recorded input_tokens + output_tokens. The columns are NOT NULL, so zero means recorded zero.");
        return body;
    }

    private static Map<String, Object> cost(int runs, long tokens, BigDecimal amount, java.util.Set<String> models) {
        String pricingStatus;
        if (runs == 0) {
            pricingStatus = "NO_USAGE";
        } else if (models.stream().anyMatch(model -> !RunService.isZeroPricedModel(model) && !RunService.isListPricedModel(model))) {
            pricingStatus = "PRICING_UNAVAILABLE";
        } else if (tokens == 0) {
            pricingStatus = "NO_TOKENS";
        } else if (models.stream().anyMatch(RunService::isListPricedModel)) {
            pricingStatus = "PRICED";
        } else {
            pricingStatus = "CONFIGURED_ZERO";
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("amount", amount);
        body.put("currency", "USD");
        body.put("pricingStatus", pricingStatus);
        body.put("definition", switch (pricingStatus) {
            case "NO_USAGE" -> "No runs in this workspace.";
            case "NO_TOKENS" -> "Runs exist and recorded zero tokens, so there is no usage to price.";
            case "CONFIGURED_ZERO" -> "Every model in this workspace has a configured price of zero. The amount is that configuration, not a measured cloud invoice.";
            case "PRICING_UNAVAILABLE" -> "At least one model has no configured price. The stored amount is not shown as a price.";
            default -> "Sum of stored estimated_cost_usd using the configured list price.";
        });
        return body;
    }

    private static Map<String, Object> queue(long openJobs) {
        var body = new LinkedHashMap<String, Object>();
        body.put("value", openJobs);
        body.put("status", "OK");
        body.put("definition", "Jobs in PENDING, RETRY, or RUNNING for this workspace. Completed and dead jobs are excluded. Jobs with no workspace are excluded. This is the Postgres job table, not Redis.");
        return body;
    }

    private static Map<String, Object> evaluations(long evaluations, long passes) {
        var body = new LinkedHashMap<String, Object>();
        body.put("value", evaluations == 0 ? null : (double) passes / evaluations);
        body.put("status", evaluations == 0 ? "NO_DATA" : "OK");
        body.put("evaluations", evaluations);
        body.put("passes", passes);
        body.put("definition", "Passed heuristic evaluations / evaluations in this workspace.");
        return body;
    }
}
