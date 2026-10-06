import assert from "node:assert/strict";
import {
  activationCopy,
  completionLabel,
  formFromVersion,
  knowledgeLabel,
  modelLabel,
  snapshotHasPrompt,
  toolCountLabel,
  validateCreateVersion,
  versionLabel,
  versionStateLabel,
  visibleTabs,
  type AgentUsage,
  type AgentVersion,
  type CreateVersionInput,
} from "./agents.ts";

const version: AgentVersion = {
  id: "v1",
  version: 1,
  current: true,
  provider: "grounded-extractive",
  model: "grounded-extractive-v1",
  temperature: 0,
  maxTokens: 900,
  timeoutMs: 60000,
  maxToolCalls: 3,
  costBudgetUsd: "0.50",
  tokenBudget: 8000,
  environment: "dev",
  promptVersionId: "p1",
  promptVersionNumber: 1,
  knowledgeBaseId: "k1",
  knowledgeBaseVersionId: "kv1",
  knowledgeName: "Support policies",
  knowledgeVersion: 1,
  knowledgeVersionStatus: "PINNED",
  createdAt: "2026-10-06T00:00:00Z",
  createdByEmail: "dev.developer@aegistrace.local",
  usedByRunCount: 7,
  toolCount: 2,
  snapshot: { version: 1, tools: ["search_knowledge", "create_support_ticket"] },
};

assert.equal(versionLabel(1), "v1");
assert.equal(versionLabel(null), "No current version");
assert.equal(versionStateLabel(true), "Current");
assert.equal(versionStateLabel(false), "Not current");
assert.equal(toolCountLabel(2), "2 tools");
assert.equal(toolCountLabel(null), "No current version");
assert.equal(knowledgeLabel("Support policies"), "Support policies");
assert.equal(knowledgeLabel(null), "Not associated");
assert.equal(modelLabel("grounded-extractive", "grounded-extractive-v1"), "grounded-extractive / grounded-extractive-v1");
assert.equal(snapshotHasPrompt({ model: "x" }), false);
assert.equal(snapshotHasPrompt({ systemPrompt: "secret" }), true);

const usage: AgentUsage = {
  runCount: 7,
  completedCount: 4,
  terminalCount: 0,
  lastRunAt: null,
  completionRate: null,
  completionStatus: "NO_DATA",
};
assert.equal(completionLabel(usage), "No data");
assert.equal(completionLabel({ ...usage, terminalCount: 4, completionRate: 0.5, completionStatus: "OK" }), "50%");

const copy = activationCopy("Support Agent", 2);
assert.equal(copy.title, "Activate Support Agent v2?");
assert.match(copy.description, /future runs/);

assert.deepEqual(visibleTabs(false).map((tab) => tab.id), ["overview", "versions", "configuration", "usage"]);
assert.ok(visibleTabs(true).some((tab) => tab.id === "audit"));

const input: CreateVersionInput = formFromVersion(version, "dev");
assert.equal(input.reusePrompt, true);
assert.equal(input.systemPrompt, "");
assert.equal(input.knowledgeBaseVersionId, "kv1");
assert.equal(validateCreateVersion(input).length, 0);
assert.ok(validateCreateVersion({ ...input, reusePrompt: false, systemPrompt: "" }).includes("A system prompt is required for a new prompt version."));
assert.ok(validateCreateVersion({ ...input, toolNames: [] }).includes("At least one tool is required."));

console.log("agent control plane tests passed");
