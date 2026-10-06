export type Citation = {
  documentTitle?: string;
  section?: string;
  pageNumber?: number | null;
  quote?: string;
  score?: number;
  chunkId?: string;
  documentId?: string;
};

export type RunCost = {
  tokens: number;
  pricingStatus: "NO_TOKENS" | "CONFIGURED_ZERO" | "PRICED" | "PRICING_UNAVAILABLE";
  amount: number | string | null;
};

export type ExecutionRun = {
  id: string;
  state: string;
  question: string;
  draftAnswer?: string | null;
  finalResponse?: string | null;
  citations: Citation[];
  inputTokens: number;
  outputTokens: number;
  provider?: string | null;
  model?: string | null;
  traceId?: string | null;
  agentName?: string;
  agentVersionId: string;
  agentVersionNumber?: number;
  failureCategory?: string | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  createdAt: string;
  startedAt?: string | null;
  endedAt?: string | null;
  durationMs?: number | null;
  cost: RunCost;
};

export type RunEvent = {
  sequence: number;
  eventType: string;
  state: string;
  payload: Record<string, unknown>;
  createdAt: string;
};

export type ToolProposal = {
  id: string;
  tool: string;
  classification?: string | null;
  risk?: string | null;
  policyDecision?: string | null;
  policyCode?: string | null;
  policyReason?: string | null;
  arguments?: Record<string, unknown>;
  reason?: string | null;
};

export type ApprovalSummary = {
  id: string;
  status: string;
  requiredRole?: string | null;
  expiresAt?: string | null;
};

export type ExecutionPayload = {
  run: ExecutionRun;
  events: RunEvent[];
  proposal: ToolProposal | null;
  approval: ApprovalSummary | null;
  capabilities: {
    canCancel: boolean;
    canReadApprovals: boolean;
    includeProposalArguments: boolean;
  };
};

export type AgentOption = {
  id: string;
  name: string;
  status: string;
  currentVersion?: number | null;
  model?: string;
  provider?: string;
};

export const TERMINAL = new Set(["COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"]);

const STATE_RANK: Record<string, number> = {
  QUEUED: 1,
  RUNNING: 2,
  RETRIEVING: 3,
  THINKING: 4,
  TOOL_PROPOSED: 5,
  APPROVAL_REQUIRED: 6,
  APPROVED: 7,
  REJECTED: 8,
  TOOL_EXECUTING: 9,
  COMPLETED: 100,
  FAILED: 100,
  CANCELLED: 100,
  TIMED_OUT: 100,
};

export const SSE_EVENT_NAMES = [
  "RUN_STARTED",
  "POLICY_DECIDED",
  "RETRIEVAL_STARTED",
  "RETRIEVAL_COMPLETED",
  "MODEL_STARTED",
  "MODEL_COMPLETED",
  "TOOL_PROPOSED",
  "APPROVAL_REQUIRED",
  "APPROVAL_APPROVED",
  "APPROVAL_REJECTED",
  "TOOL_STARTED",
  "TOOL_COMPLETED",
  "RUN_COMPLETED",
  "RUN_FAILED",
  "RUN_CANCELLED",
  "RUN_TIMED_OUT",
] as const;

const EVENT_LABELS: Record<string, string> = {
  RUN_STARTED: "Run created",
  RETRIEVAL_STARTED: "Retrieval started",
  RETRIEVAL_COMPLETED: "Retrieval completed",
  MODEL_STARTED: "Model processing",
  MODEL_COMPLETED: "Response generated",
  TOOL_PROPOSED: "Tool proposed",
  POLICY_DECIDED: "Policy evaluated",
  APPROVAL_REQUIRED: "Approval required",
  APPROVAL_APPROVED: "Approval granted",
  APPROVAL_REJECTED: "Approval declined",
  TOOL_STARTED: "Tool executing",
  TOOL_COMPLETED: "Tool executed",
  RUN_COMPLETED: "Run completed",
  RUN_FAILED: "Run failed",
  RUN_CANCELLED: "Run cancelled",
  RUN_TIMED_OUT: "Run timed out",
};

export function isTerminal(state: string | undefined): boolean {
  return !!state && TERMINAL.has(state);
}

export function mergeEvents(current: RunEvent[], incoming: RunEvent[]): RunEvent[] {
  const bySequence = new Map<number, RunEvent>();
  for (const event of current) bySequence.set(event.sequence, event);
  for (const event of incoming) bySequence.set(event.sequence, event);
  return [...bySequence.values()].sort((a, b) => a.sequence - b.sequence);
}

export function preferAuthoritativeState(previous: string | undefined, next: string): string {
  if (!previous) return next;
  if (isTerminal(previous) && !isTerminal(next)) return previous;
  const prevRank = STATE_RANK[previous] ?? 0;
  const nextRank = STATE_RANK[next] ?? 0;
  if (isTerminal(next)) return next;
  return nextRank >= prevRank ? next : previous;
}

export function eventLabel(type: string): string {
  return EVENT_LABELS[type] || type.replaceAll("_", " ").toLowerCase();
}

export function eventSummary(event: RunEvent): string {
  const payload = event.payload || {};
  if (payload.chunkCount !== undefined) return `${payload.chunkCount} sources retrieved`;
  if (payload.decision) return `Policy decision: ${payload.decision}${payload.code ? ` (${payload.code})` : ""}`;
  if (payload.tool && event.eventType === "TOOL_PROPOSED") return `Proposed ${payload.tool}`;
  if (payload.tool && event.eventType === "RETRIEVAL_STARTED") return "Retrieving knowledge";
  if (payload.ticketId) return `Ticket ${payload.ticketId}`;
  if (payload.category) return String(payload.category);
  if (payload.abstained === true) return "Insufficient evidence";
  if (payload.supported === true) return "Grounded response generated";
  return "";
}

export function compactRunId(id: string): string {
  const compact = id.replaceAll("-", "").slice(0, 8).toUpperCase();
  return `RUN-${compact}`;
}

export function formatCost(cost: RunCost | undefined): string | null {
  if (!cost) return null;
  if (cost.pricingStatus === "NO_TOKENS") return null;
  if (cost.pricingStatus === "PRICING_UNAVAILABLE") return "Pricing unavailable";
  if (cost.pricingStatus === "CONFIGURED_ZERO") return "$0.00 · configured zero-price model";
  const amount = Number(cost.amount);
  if (!Number.isFinite(amount)) return "Pricing unavailable";
  return `$${amount.toFixed(2)}`;
}

export function formatTokens(cost: RunCost | undefined): string | null {
  if (!cost || cost.pricingStatus === "NO_TOKENS" || cost.tokens <= 0) return null;
  return `${cost.tokens.toLocaleString("en-US")} tokens`;
}

export function activeAgents(agents: AgentOption[]): AgentOption[] {
  return agents.filter((agent) => agent.status === "ACTIVE");
}

export function toolDisplayName(tool: string): string {
  if (tool === "create_support_ticket") return "Create support ticket";
  if (tool === "search_knowledge") return "Search knowledge";
  return tool.replaceAll("_", " ");
}

export const DRAFT_KEY = "aegis.support.draft";
export const QUESTION_MAX = 4000;
export const DEMO_QUESTION = "Why was my application rejected, and what should I check before reapplying?";
