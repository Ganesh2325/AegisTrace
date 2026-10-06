package com.aegistrace.metrics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsCalculatorTest {
    @Test
    void percentileContMatchesPostgresOnOneThroughTenSeconds() {
        var values = new ArrayList<Double>();
        for (int seconds = 1; seconds <= 10; seconds++) {
            values.add(seconds * 1000d);
        }
        assertEquals(5500d, MetricsCalculator.percentileCont(values, 0.50), 0.001);
        assertEquals(9550d, MetricsCalculator.percentileCont(values, 0.95), 0.001);
        assertEquals(9910d, MetricsCalculator.percentileCont(values, 0.99), 0.001);
    }

    @Test
    void percentileOfOneSampleIsThatSample() {
        assertEquals(1500d, MetricsCalculator.percentileCont(List.of(1500d), 0.95));
        assertNull(MetricsCalculator.percentileCont(List.of(), 0.50));
    }

    @Test
    void tenCompletedRunsAreFullySuccessful() {
        var summary = MetricsCalculator.summarize(runs(10, 0, 0, 0), List.of(), 0, 0, 0, 0, 0);
        assertEquals(1d, rate(summary, "completion"));
        assertEquals(0d, rate(summary, "failure"));
        assertEquals("OK", status(summary, "completion"));
    }

    @Test
    void halfCompletedHalfFailed() {
        var summary = MetricsCalculator.summarize(runs(5, 5, 0, 0), List.of(), 0, 0, 0, 0, 0);
        assertEquals(0.5d, rate(summary, "completion"));
        assertEquals(0.5d, rate(summary, "failure"));
    }

    @Test
    void timeoutsCountAsUnsuccessfulAndNotAsCompleted() {
        var summary = MetricsCalculator.summarize(runs(5, 3, 2, 0), List.of(), 0, 0, 0, 0, 0);
        assertEquals(0.5d, rate(summary, "completion"));
        assertEquals(0.5d, rate(summary, "failure"));
        assertEquals(2L, count(summary, "timedOut"));
    }

    @Test
    void cancelledRunsAreTerminalButNotFailures() {
        var summary = MetricsCalculator.summarize(runs(5, 0, 0, 5), List.of(), 0, 0, 0, 0, 0);
        assertEquals(0.5d, rate(summary, "completion"));
        assertEquals(0d, rate(summary, "failure"));
        assertEquals(5L, count(summary, "cancelled"));
    }

    @Test
    void onlyCancelledRunsHaveZeroCompletionAndZeroFailure() {
        var summary = MetricsCalculator.summarize(runs(0, 0, 0, 4), List.of(), 0, 0, 0, 0, 0);
        assertEquals(0d, rate(summary, "completion"));
        assertEquals(0d, rate(summary, "failure"));
    }

    @Test
    void noTerminalRunsAreNotAZeroRate() {
        var summary = MetricsCalculator.summarize(List.of(
                run("QUEUED", 0d),
                run("APPROVAL_REQUIRED", null)
        ), List.of(), 0, 0, 0, 0, 0);
        assertEquals("NO_DATA", status(summary, "completion"));
        assertEquals("NO_DATA", status(summary, "failure"));
        assertNull(value(summary, "completion"));
        assertNull(value(summary, "failure"));
        assertEquals("NO_DATA", latency(summary).get("status"));
        assertEquals(2L, count(summary, "runs"));
        assertEquals(0L, count(summary, "terminalRuns"));
    }

    @Test
    void inProgressRunsDoNotDiluteAFinishedSuccess() {
        var samples = new ArrayList<MetricsCalculator.RunSample>();
        samples.addAll(runs(5, 0, 0, 0));
        samples.add(run("RUNNING", null));
        samples.add(run("APPROVAL_REQUIRED", null));
        var summary = MetricsCalculator.summarize(samples, List.of(), 0, 0, 0, 0, 0);
        assertEquals(1d, rate(summary, "completion"));
        assertEquals(0d, rate(summary, "failure"));
        assertEquals(7L, count(summary, "runs"));
        assertEquals(2L, count(summary, "inProgress"));
    }

    @Test
    void invalidAndUnfinishedDurationsStayOutOfPercentiles() {
        var summary = MetricsCalculator.summarize(List.of(
                run("COMPLETED", 1000d),
                run("COMPLETED", 3000d),
                run("COMPLETED", null),
                run("FAILED", -5d),
                run("RUNNING", 99999d),
                run("QUEUED", -1d)
        ), List.of(), 0, 0, 0, 0, 0);
        var latency = latency(summary);
        assertEquals(2, ((Number) latency.get("sampleCount")).intValue());
        assertEquals(3L, ((Number) latency.get("invalidDurationCount")).longValue());
        assertEquals(2000d, ((Number) latency.get("p50Ms")).doubleValue(), 0.001);
    }

    @Test
    void pendingApprovalsDoNotChangeTheApprovedWait() {
        var summary = MetricsCalculator.summarize(List.of(), List.of(
                new MetricsCalculator.ApprovalSample("PENDING", null),
                new MetricsCalculator.ApprovalSample("APPROVED", 5000d),
                new MetricsCalculator.ApprovalSample("CANCELLED", 8000d)
        ), 0, 0, 0, 0, 0);
        var wait = approval(summary);
        assertEquals(5000d, ((Number) group(wait, "approved").get("valueMs")).doubleValue(), 0.001);
        assertEquals(1, ((Number) group(wait, "approved").get("sampleCount")).intValue());
        assertEquals("NO_DATA", group(wait, "expired").get("status"));
        assertEquals(1L, count(summary, "pendingApprovals"));
    }

    @Test
    void rejectedExpiredAndInvalidWaitsStayInTheirOwnPopulations() {
        var summary = MetricsCalculator.summarize(List.of(), List.of(
                new MetricsCalculator.ApprovalSample("APPROVED", 0d),
                new MetricsCalculator.ApprovalSample("APPROVED", 5000d),
                new MetricsCalculator.ApprovalSample("REJECTED", 2000d),
                new MetricsCalculator.ApprovalSample("EXPIRED", 9000d),
                new MetricsCalculator.ApprovalSample("EXPIRED", null),
                new MetricsCalculator.ApprovalSample("APPROVED", -10d)
        ), 0, 0, 0, 0, 0);
        var wait = approval(summary);
        assertEquals(2500d, ((Number) group(wait, "approved").get("valueMs")).doubleValue(), 0.001);
        assertEquals(1, ((Number) group(wait, "approved").get("invalidCount")).intValue());
        assertEquals(2000d, ((Number) group(wait, "rejected").get("valueMs")).doubleValue(), 0.001);
        assertEquals(9000d, ((Number) group(wait, "expired").get("valueMs")).doubleValue(), 0.001);
        assertEquals(1, ((Number) group(wait, "expired").get("invalidCount")).intValue());
    }

    @Test
    void workspacesDoNotShareSamples() {
        var workspaceA = MetricsCalculator.summarize(runs(5, 0, 0, 0), List.of(), 1, 0, 1, 0, 0);
        var workspaceB = MetricsCalculator.summarize(runs(20, 0, 0, 0), List.of(), 4, 2, 4, 0, 0);
        assertEquals(5L, count(workspaceA, "runs"));
        assertEquals(20L, count(workspaceB, "runs"));
        assertEquals(1L, count(workspaceA, "queueDepth"));
        assertEquals(4L, count(workspaceB, "queueDepth"));
        assertEquals(0d, rate(workspaceA, "policyDenial"));
        assertEquals(0.5d, rate(workspaceB, "policyDenial"));
    }

    @Test
    void emptyWorkspaceDoesNotInventRatesOrPrices() {
        var summary = MetricsCalculator.summarize(List.of(), List.of(), 0, 0, 0, 0, 0);
        assertEquals(0L, count(summary, "runs"));
        assertEquals(0L, count(summary, "tokens"));
        assertEquals(0L, count(summary, "queueDepth"));
        assertEquals("NO_DATA", status(summary, "completion"));
        assertEquals("NO_DATA", status(summary, "failure"));
        assertEquals("NO_DATA", status(summary, "policyDenial"));
        assertEquals("NO_DATA", latency(summary).get("status"));
        assertEquals("NO_DATA", group(approval(summary), "approved").get("status"));
        var cost = group(summary, "cost");
        assertEquals("NO_USAGE", cost.get("pricingStatus"));
        assertEquals(0, ((BigDecimal) cost.get("amount")).compareTo(BigDecimal.ZERO));
    }

    @Test
    void zeroPriceModelIsLabeledInsteadOfLookingLikeAnInvoice() {
        var summary = MetricsCalculator.summarize(List.of(
                new MetricsCalculator.RunSample("COMPLETED", 1000d, 400, 167, BigDecimal.ZERO, "grounded-extractive-v1")
        ), List.of(), 0, 0, 1, 1, 1);
        var cost = group(summary, "cost");
        assertEquals("CONFIGURED_ZERO", cost.get("pricingStatus"));
        assertEquals(0, ((BigDecimal) cost.get("amount")).compareTo(BigDecimal.ZERO));
        assertEquals(567L, count(summary, "tokens"));
    }

    @Test
    void unknownModelDoesNotPresentStoredZeroAsAPrice() {
        var summary = MetricsCalculator.summarize(List.of(
                new MetricsCalculator.RunSample("COMPLETED", 1000d, 10, 10, BigDecimal.ZERO, "some-other-model")
        ), List.of(), 0, 0, 0, 0, 0);
        assertEquals("PRICING_UNAVAILABLE", group(summary, "cost").get("pricingStatus"));
    }

    @Test
    void recordedZeroTokensAreNotAConfiguredPrice() {
        var summary = MetricsCalculator.summarize(List.of(
                new MetricsCalculator.RunSample("FAILED", 1000d, 0, 0, BigDecimal.ZERO, "gpt-4o-mini")
        ), List.of(), 0, 0, 0, 0, 0);
        assertEquals("NO_TOKENS", group(summary, "cost").get("pricingStatus"));
    }

    @Test
    void listPriceModelReportsPriced() {
        var summary = MetricsCalculator.summarize(List.of(
                new MetricsCalculator.RunSample("COMPLETED", 1000d, 1_000_000, 1_000_000, new BigDecimal("0.750000"), "gpt-4o-mini")
        ), List.of(), 0, 0, 0, 0, 0);
        var cost = group(summary, "cost");
        assertEquals("PRICED", cost.get("pricingStatus"));
        assertEquals(0, new BigDecimal("0.75").compareTo((BigDecimal) cost.get("amount")));
    }

    @Test
    void policyDenialIsNoDataUntilADecisionExists() {
        var empty = MetricsCalculator.summarize(List.of(), List.of(), 0, 0, 0, 0, 0);
        assertEquals("NO_DATA", status(empty, "policyDenial"));
        var denied = MetricsCalculator.summarize(List.of(), List.of(), 0, 1, 4, 0, 0);
        assertEquals(0.25d, rate(denied, "policyDenial"));
    }

    @Test
    void highLatencyFlagUsesTheDocumentedAttentionLine() {
        var calm = MetricsCalculator.summarize(List.of(run("COMPLETED", 1000d)), List.of(), 0, 0, 0, 0, 0);
        assertFalse((Boolean) latency(calm).get("high"));
        var slow = MetricsCalculator.summarize(List.of(run("COMPLETED", 300_000d)), List.of(), 0, 0, 0, 0, 0);
        assertTrue((Boolean) latency(slow).get("high"));
        assertEquals(MetricsCalculator.LATENCY_ATTENTION_MS, ((Number) latency(slow).get("attentionMs")).longValue());
    }

    @Test
    void everySummaryQueryIsBoundToTheWorkspace() {
        assertTrue(MetricsService.RUNS_SQL.contains("where r.workspace_id = :workspace"));
        assertTrue(MetricsService.APPROVALS_SQL.contains("where a.workspace_id = :workspace"));
        assertTrue(MetricsService.COUNTS_SQL.contains("jobs"));
        assertTrue(MetricsService.COUNTS_SQL.contains("workspace_id = :workspace and status in ('PENDING', 'RETRY', 'RUNNING')"));
        assertTrue(MetricsService.COUNTS_SQL.contains("r.workspace_id = :workspace and p.policy_decision"));
        assertTrue(MetricsService.COUNTS_SQL.contains("evaluations where workspace_id = :workspace"));
        assertFalse(MetricsService.COUNTS_SQL.contains("from jobs where status in"));
    }

    @Test
    void rollingOneHourIsOneHour() {
        Instant end = Instant.parse("2026-10-05T12:00:00Z");
        var window = MetricsCalculator.Window.parse("1H", end);
        assertEquals("1H", window.code());
        assertEquals(1, window.hours());
        assertEquals(end.minusSeconds(3600L), window.start());
    }

    @Test
    void rollingSevenDaysIsOneHundredSixtyEightHours() {
        Instant end = Instant.parse("2026-10-05T12:00:00Z");
        var window = MetricsCalculator.Window.parse("7D", end);
        assertEquals("7D", window.code());
        assertEquals(168, window.hours());
        assertEquals(end.minusSeconds(168 * 3600L), window.start());
        assertEquals(end, window.end());
        assertTrue(window.sqlPredicate("r.created_at").contains(":windowStart"));
        assertTrue(window.sqlPredicate("r.created_at").contains(":windowEnd "));
    }

    @Test
    void allTimeWindowHasNoCreatedAtPredicate() {
        var window = MetricsCalculator.Window.parse("ALL", Instant.parse("2026-10-05T12:00:00Z"));
        assertEquals("ALL_TIME", window.code());
        assertEquals("", window.sqlPredicate("r.created_at"));
    }

    private static List<MetricsCalculator.RunSample> runs(int completed, int failed, int timedOut, int cancelled) {
        var samples = new ArrayList<MetricsCalculator.RunSample>();
        for (int i = 0; i < completed; i++) samples.add(run("COMPLETED", (i + 1) * 1000d));
        for (int i = 0; i < failed; i++) samples.add(run("FAILED", 2000d));
        for (int i = 0; i < timedOut; i++) samples.add(run("TIMED_OUT", 3000d));
        for (int i = 0; i < cancelled; i++) samples.add(run("CANCELLED", 4000d));
        return samples;
    }

    private static MetricsCalculator.RunSample run(String state, Double durationMs) {
        return new MetricsCalculator.RunSample(state, durationMs, 0, 0, BigDecimal.ZERO, "grounded-extractive-v1");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> group(Map<String, Object> body, String key) {
        return (Map<String, Object>) body.get(key);
    }

    private static Map<String, Object> latency(Map<String, Object> body) {
        return group(body, "latency");
    }

    private static Map<String, Object> approval(Map<String, Object> body) {
        return group(body, "approvalWait");
    }

    private static Object value(Map<String, Object> body, String key) {
        return group(body, key).get("value");
    }

    private static String status(Map<String, Object> body, String key) {
        return (String) group(body, key).get("status");
    }

    private static double rate(Map<String, Object> body, String key) {
        return ((Number) value(body, key)).doubleValue();
    }

    private static long count(Map<String, Object> body, String key) {
        return ((Number) value(body, key)).longValue();
    }
}
