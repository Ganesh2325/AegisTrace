export type CountMetric = { value: number; status: string; definition: string };
export type RateMetric = { value: number | null; status: string; definition: string; numerator: number; terminal: number };
export type WaitMetric = { status: string; valueMs: number | null; sampleCount: number };

export type OperationsSummary = {
  window: string;
  windowHours: number | null;
  timezone: string;
  windowStart: string | null;
  windowEnd: string;
  runs: CountMetric;
  inProgress: CountMetric;
  terminalRuns: CountMetric;
  completed: CountMetric;
  failed: CountMetric;
  timedOut: CountMetric;
  cancelled: CountMetric;
  completion: RateMetric;
  failure: RateMetric;
  latency: {
    status: string;
    definition: string;
    p50Ms: number | null;
    p95Ms: number | null;
    p99Ms: number | null;
    sampleCount: number;
    invalidDurationCount: number;
    high: boolean;
    attentionMs: number;
  };
  approvalWait: { definition: string; approved: WaitMetric; rejected: WaitMetric; expired: WaitMetric };
  pendingApprovals: CountMetric;
  policyDenial: RateMetric & { denials: number; decisions: number };
  tokens: CountMetric;
  cost: { amount: number; currency: string; pricingStatus: string; definition: string };
  queueDepth: CountMetric;
};

export type OperationsRun = {
  id: string;
  state: string;
  failureCategory: string | null;
  estimatedCostUsd: number | string | null;
  createdAt: string;
  startedAt: string | null;
  endedAt: string | null;
  durationMs: number | null;
  question: string;
  userId: string;
  agentId: string;
  agentName: string;
  href: string;
};

export type AttentionItem = {
  severity: "HIGH" | "MEDIUM" | "LOW" | string;
  code: string;
  title: string;
  reason: string;
  runId: string | null;
  href: string;
};

export type ActivityEvent = {
  eventType: string;
  state: string;
  createdAt: string;
  runId: string;
  agentName: string;
};

export type ActivityPoint = { bucket: string; runs: number; completed: number; unsuccessful: number };
export type LatencyPoint = { bucket: string; p50Ms: number | null; p95Ms: number | null; p99Ms: number | null; samples: number };

export type AgentHealth = {
  id: string;
  name: string;
  status: string;
  version: number | null;
  runs: number;
  completed: number;
  unsuccessful: number;
  lastRunAt: string | null;
  completion: number | null;
};

export type ApprovalSummary = {
  id: string;
  status: string;
  requestedAt: string;
  decidedAt: string | null;
  runId: string;
  tool: string;
};

export type OperationsOverview = {
  generatedAt: string;
  summary: OperationsSummary;
  activeRuns: OperationsRun[];
  recentRuns: { items: OperationsRun[]; page: number; size: number; total: number; filter: string };
  recentFailures: OperationsRun[];
  approvals: ApprovalSummary[];
  activity: ActivityEvent[];
  agents: AgentHealth[];
  activityTrend: ActivityPoint[];
  latencyTrend: LatencyPoint[];
  attention: AttentionItem[];
  capabilities: { canCreateRun: boolean; canReadApprovals: boolean };
};

export const WINDOWS = ["24H", "7D", "30D", "ALL"] as const;
export type WindowCode = (typeof WINDOWS)[number];
export const RUN_FILTERS = ["ALL", "COMPLETED", "FAILED", "TIMED_OUT", "CANCELLED", "ACTIVE"] as const;
export type RunFilter = (typeof RUN_FILTERS)[number];
