export type AgentStatus = "ACTIVE" | "INACTIVE";

export type ModelConfig = {
  provider: string;
  model: string;
  temperature: number;
  maxTokens: number;
  timeoutMs: number;
  tokenBudget: number | null;
  costBudgetUsd: number | string | null;
};

export type AgentLimits = {
  maxToolCalls: number;
  timeoutMs: number;
  tokenBudget: number | null;
  costBudgetUsd: number | string | null;
};

export type ToolAssignment = {
  name: string;
  classification: string;
  risk: string;
  approvalRequired: boolean;
  enabled: boolean;
};

export type PolicyReference = {
  tool: string;
  approvalRequired: boolean;
  classification: string;
};

export type KnowledgeReference = {
  knowledgeBaseId: string;
  knowledgeBaseVersionId: string | null;
  knowledgeName: string;
  knowledgeVersion: number | null;
  knowledgeVersionStatus: "PINNED" | "MISSING" | string;
};

export type PromptVersion = {
  id: string;
  version: number;
};

export type AgentVersion = {
  id: string;
  version: number;
  current: boolean;
  provider: string;
  model: string;
  temperature: number;
  maxTokens: number;
  timeoutMs: number;
  maxToolCalls: number;
  costBudgetUsd: number | string | null;
  tokenBudget: number | null;
  environment: string;
  promptVersionId: string;
  promptVersionNumber: number;
  knowledgeBaseId: string;
  knowledgeName: string;
  knowledgeBaseVersionId: string | null;
  knowledgeVersion: number | null;
  knowledgeVersionStatus: string;
  createdAt: string;
  createdByEmail: string;
  usedByRunCount: number;
  toolCount: number;
  snapshot: Record<string, unknown>;
  tools?: ToolAssignment[];
};

export type AgentUsage = {
  runCount: number;
  completedCount: number;
  terminalCount: number;
  lastRunAt: string | null;
  completionRate: number | null;
  completionStatus: "NO_DATA" | "OK" | string;
};

export type AgentRunSummary = {
  id: string;
  state: string;
  createdAt: string;
  agentVersionNumber: number;
};

export type AgentCapabilities = {
  canConfigure: boolean;
  canChangeStatus: boolean;
};

export type AgentSummary = {
  id: string;
  name: string;
  description: string;
  status: AgentStatus | string;
  createdAt: string;
  updatedAt: string;
  currentVersion: number | null;
  currentVersionId: string | null;
  model: string | null;
  provider: string | null;
  knowledgeName: string | null;
  toolCount: number | null;
  runCount: number;
  lastRunAt: string | null;
};

export type AgentDetail = {
  id: string;
  name: string;
  description: string;
  status: AgentStatus | string;
  createdAt: string;
  updatedAt: string;
  workspaceName: string;
  createdByEmail: string;
  activeVersion: AgentVersion | null;
  usage: AgentUsage;
  recentRuns: AgentRunSummary[];
  capabilities: AgentCapabilities;
};

export type AgentAuditEvent = {
  id: string;
  action: string;
  resourceType: string;
  resourceId: string;
  createdAt: string;
};

export type RegisteredTool = {
  name: string;
  description: string;
  classification: string;
  requiredPermission: string;
  risk: string;
  approvalRequired: boolean;
  timeoutMs: number;
  idempotencyRequired: boolean;
};

export type KnowledgeOption = {
  id: string;
  name: string;
  slug: string;
  embeddingModel: string;
  status: string;
};

export type CreateVersionInput = {
  provider: string;
  model: string;
  temperature: number;
  maxTokens: number;
  timeoutMs: number;
  maxToolCalls: number;
  costBudgetUsd: number;
  tokenBudget: number;
  systemPrompt: string;
  promptVersionId: string;
  knowledgeBaseId: string;
  knowledgeBaseVersionId: string;
  toolNames: string[];
  environment: string;
  reusePrompt: boolean;
};

export type AgentTab = "overview" | "versions" | "configuration" | "usage" | "audit";

export function versionLabel(version: number | null | undefined): string {
  if (version == null) return "No current version";
  return `v${version}`;
}

export function versionStateLabel(current: boolean): string {
  return current ? "Current" : "Not current";
}

export function toolCountLabel(count: number | null | undefined): string {
  if (count == null) return "No current version";
  if (count === 1) return "1 tool";
  return `${count} tools`;
}

export function knowledgeLabel(name: string | null | undefined): string {
  return name || "Not associated";
}

export function modelLabel(provider: string | null | undefined, model: string | null | undefined): string {
  if (!provider && !model) return "No current version";
  if (provider && model) return `${provider} / ${model}`;
  return model || provider || "No current version";
}

export function completionLabel(usage: AgentUsage | undefined): string {
  if (!usage || usage.completionStatus === "NO_DATA" || usage.completionRate == null) return "No data";
  return `${Math.round(usage.completionRate * 100)}%`;
}

export function policyFromTools(tools: ToolAssignment[]): PolicyReference[] {
  return tools.map((tool) => ({
    tool: tool.name,
    approvalRequired: tool.approvalRequired,
    classification: tool.classification,
  }));
}

export function snapshotHasPrompt(snapshot: Record<string, unknown> | undefined): boolean {
  return snapshot != null && Object.prototype.hasOwnProperty.call(snapshot, "systemPrompt");
}

export function validateCreateVersion(input: CreateVersionInput): string[] {
  const errors: string[] = [];
  if (!input.provider.trim()) errors.push("Provider is required.");
  if (!input.model.trim()) errors.push("Model is required.");
  if (!input.environment.trim()) errors.push("Environment is required.");
  if (!input.knowledgeBaseId) errors.push("A knowledge base is required.");
  if (!input.knowledgeBaseVersionId) errors.push("A knowledge version is required.");
  if (input.toolNames.length === 0) errors.push("At least one tool is required.");
  if (input.maxToolCalls < 1 || input.maxTokens < 1 || input.timeoutMs < 1000 || input.tokenBudget < 1) {
    errors.push("Budgets and timeouts must be positive.");
  }
  if (input.reusePrompt && !input.promptVersionId) errors.push("Select an existing prompt version.");
  if (!input.reusePrompt && !input.systemPrompt.trim()) errors.push("A system prompt is required for a new prompt version.");
  return errors;
}

export function formFromVersion(version: AgentVersion | null, fallbackEnvironment: string): CreateVersionInput {
  const tools = version?.tools?.map((tool) => tool.name) || (typeof version?.snapshot.tools === "object" && Array.isArray(version.snapshot.tools)
    ? version.snapshot.tools.map(String)
    : []);
  return {
    provider: version?.provider || "",
    model: version?.model || "",
    temperature: version?.temperature ?? 0,
    maxTokens: version?.maxTokens ?? 900,
    timeoutMs: version?.timeoutMs ?? 60000,
    maxToolCalls: version?.maxToolCalls ?? 3,
    costBudgetUsd: Number(version?.costBudgetUsd ?? 0.5),
    tokenBudget: version?.tokenBudget ?? 8000,
    systemPrompt: "",
    promptVersionId: version?.promptVersionId || "",
    knowledgeBaseId: version?.knowledgeBaseId || "",
    knowledgeBaseVersionId: version?.knowledgeBaseVersionId || "",
    toolNames: tools,
    environment: version?.environment || fallbackEnvironment,
    reusePrompt: Boolean(version?.promptVersionId),
  };
}

export function activationCopy(agentName: string, version: number): { title: string; description: string } {
  return {
    title: `Activate ${agentName} v${version}?`,
    description: `This will make v${version} the current configuration for future runs. Existing runs keep the version they started with.`,
  };
}

export function visibleTabs(canReadAudit: boolean): { id: AgentTab; label: string }[] {
  const tabs: { id: AgentTab; label: string }[] = [
    { id: "overview", label: "Overview" },
    { id: "versions", label: "Versions" },
    { id: "configuration", label: "Configuration" },
    { id: "usage", label: "Usage" },
  ];
  if (canReadAudit) tabs.push({ id: "audit", label: "Audit" });
  return tabs;
}

export function formatLimitTimeout(ms: number | null | undefined): string {
  if (ms == null) return "Not available";
  if (ms % 1000 === 0) return `${ms / 1000}s`;
  return `${ms} ms`;
}
