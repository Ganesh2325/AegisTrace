import assert from "node:assert/strict";
import {
  activeAgents,
  compactRunId,
  eventLabel,
  eventSummary,
  formatCost,
  formatTokens,
  isTerminal,
  mergeEvents,
  preferAuthoritativeState,
  type RunEvent,
} from "./supportRun.ts";

const event = (sequence: number, eventType: string, state: string, payload: Record<string, unknown> = {}): RunEvent => ({
  sequence,
  eventType,
  state,
  payload,
  createdAt: "2026-10-06T00:00:00Z",
});

assert.equal(isTerminal("COMPLETED"), true);
assert.equal(isTerminal("RUNNING"), false);
assert.equal(preferAuthoritativeState("COMPLETED", "RUNNING"), "COMPLETED");
assert.equal(preferAuthoritativeState("RUNNING", "RETRIEVING"), "RETRIEVING");
assert.equal(preferAuthoritativeState("THINKING", "RUNNING"), "THINKING");
assert.equal(preferAuthoritativeState("APPROVAL_REQUIRED", "COMPLETED"), "COMPLETED");

const merged = mergeEvents(
  [event(1, "RUN_STARTED", "RUNNING"), event(2, "RETRIEVAL_STARTED", "RETRIEVING")],
  [event(2, "RETRIEVAL_STARTED", "RETRIEVING"), event(3, "RETRIEVAL_COMPLETED", "RETRIEVING")],
);
assert.deepEqual(merged.map((row) => row.sequence), [1, 2, 3]);

assert.equal(eventLabel("RETRIEVAL_COMPLETED"), "Retrieval completed");
assert.equal(eventSummary(event(1, "RETRIEVAL_COMPLETED", "RETRIEVING", { chunkCount: 4 })), "4 sources retrieved");
assert.equal(eventSummary(event(2, "POLICY_DECIDED", "TOOL_PROPOSED", { decision: "REQUIRE_APPROVAL", code: "WRITE_REQUIRES_APPROVAL" })), "Policy decision: REQUIRE_APPROVAL (WRITE_REQUIRES_APPROVAL)");
assert.equal(eventSummary(event(3, "TOOL_PROPOSED", "TOOL_PROPOSED", { tool: "create_support_ticket" })), "Proposed create_support_ticket");
assert.ok(!eventSummary(event(4, "MODEL_STARTED", "THINKING", { thought: "private chain" })).includes("private"));

assert.equal(compactRunId("11111111-1111-1111-1111-111111111111"), "RUN-11111111");
assert.equal(formatCost({ tokens: 0, pricingStatus: "NO_TOKENS", amount: 0 }), null);
assert.equal(formatCost({ tokens: 250, pricingStatus: "CONFIGURED_ZERO", amount: 0 }), "$0.00 · configured zero-price model");
assert.equal(formatTokens({ tokens: 3351, pricingStatus: "CONFIGURED_ZERO", amount: 0 }), "3,351 tokens");
assert.equal(activeAgents([{ id: "1", name: "Internal Support Agent", status: "ACTIVE" }, { id: "2", name: "Off", status: "INACTIVE" }]).length, 1);

let submits = 0;
function startRun(busy: boolean, question: string) {
  if (busy || !question.trim()) return;
  submits += 1;
}
startRun(false, "hello");
startRun(true, "hello");
startRun(true, "hello");
assert.equal(submits, 1);

console.log("support run tests passed");
