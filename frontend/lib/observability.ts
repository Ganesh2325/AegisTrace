export type CountMetric = { value: number; status: string; definition: string; unit?: string };
export type RateMetric = { value: number | null; status: string; definition: string; numerator: number; terminal: number };

export const OBS_WINDOWS = ["1H", "24H", "7D", "30D"] as const;
export type ObsWindow = (typeof OBS_WINDOWS)[number];

export type TelemetryStatus = {
  traces: string;
  metricsSql?: string;
  prometheus: string;
  samplingProbability: number;
  jaegerUrl?: string | null;
  grafanaUrl?: string | null;
};

export type TraceListItem = {
  traceId: string;
  runId: string;
  operation: string;
  status: string;
  runState: string;
  durationMs: number | null;
  agent: string;
  agentVersion: number | null;
  startedAt: string;
  environment: string;
};

export type RecentError = {
  runId: string;
  traceId: string;
  service: string;
  operation: string;
  category: string | null;
  code: string | null;
  state: string;
  at: string;
  agent: string;
};

export type ObservabilityOverview = {
  generatedAt: string;
  window: string;
  windowHours: number | null;
  timezone: string;
  metrics: {
    runs: CountMetric;
    inProgress: CountMetric;
    failed: CountMetric;
    completion: RateMetric;
    failure: RateMetric;
    latency: { status: string; definition: string; p50Ms: number | null; p95Ms: number | null; p99Ms: number | null; sampleCount: number };
    approvalWait: { definition: string; approved: { status: string; valueMs: number | null; sampleCount: number } };
    policyDenial: RateMetric & { denials: number; decisions: number };
    tokens: CountMetric;
    cost: { amount: number; currency: string; pricingStatus: string; definition: string };
    queueDepth: CountMetric;
  };
  toolExecutions: CountMetric;
  recentErrors: RecentError[];
  recentTraces: TraceListItem[];
  telemetry: TelemetryStatus;
  services: { name: string; telemetry: string }[];
  environment: string;
};

export type TraceSpan = {
  spanId: string;
  parentSpanId: string;
  name: string;
  service: string;
  kind: string;
  startUs: number;
  durationUs: number;
  durationMs: number;
  start: string | null;
  status: string;
  attributes: Record<string, string | number | boolean>;
};

export type ProductPhase = {
  name: string;
  kind: string;
  start: string | null;
  end: string | null;
  durationMs: number | null;
  status: string;
};

export type TraceDetail = {
  traceId: string;
  runId: string;
  status: string;
  runState: string;
  failureCategory: string | null;
  errorCode: string | null;
  durationMs: number | null;
  createdAt: string;
  startedAt: string | null;
  endedAt: string | null;
  environment: string;
  agentId: string;
  agentName: string;
  agentVersion: number;
  agentVersionId: string;
  knowledgeBaseId: string | null;
  knowledgeVersionId: string | null;
  knowledgeVersion: number | null;
  provider: string;
  model: string;
  approvalId: string | null;
  toolProposalId: string | null;
  productPhases: ProductPhase[];
  phaseTotalsMs: Record<string, number>;
  productEvents: { kind: string; sequence: number; name: string; state: string; at: string }[];
  telemetry: { status: string; message: string; spanCount: number };
  spans: TraceSpan[];
  jaegerUrl: string | null;
  grafanaUrl: string | null;
};

export function parseObsWindow(value: string | null): ObsWindow {
  const next = (value || "24H").toUpperCase();
  return OBS_WINDOWS.includes(next as ObsWindow) ? (next as ObsWindow) : "24H";
}

export function compactId(id: string, size = 8) {
  if (!id) return "";
  return id.length <= size ? id : `${id.slice(0, size)}…`;
}

export function waterfallRows(spans: TraceSpan[]) {
  if (spans.length === 0) return [];
  const min = Math.min(...spans.map((span) => span.startUs || 0));
  const max = Math.max(...spans.map((span) => (span.startUs || 0) + (span.durationUs || 0)));
  const total = Math.max(max - min, 1);
  const byId = new Map(spans.map((span) => [span.spanId, span]));
  const children = new Map<string, TraceSpan[]>();
  const roots: TraceSpan[] = [];
  for (const span of spans) {
    const parent = span.parentSpanId && byId.has(span.parentSpanId) ? span.parentSpanId : "";
    if (!parent) roots.push(span);
    else {
      const list = children.get(parent) ?? [];
      list.push(span);
      children.set(parent, list);
    }
  }
  const byStart = (left: TraceSpan, right: TraceSpan) => (left.startUs || 0) - (right.startUs || 0);
  const ordered: { span: TraceSpan; depth: number }[] = [];
  const walk = (span: TraceSpan, depth: number) => {
    ordered.push({ span, depth });
    for (const child of (children.get(span.spanId) ?? []).sort(byStart)) walk(child, depth + 1);
  };
  for (const root of roots.sort(byStart)) walk(root, 0);
  return ordered.map(({ span, depth }) => ({
    ...span,
    depth,
    offsetPct: ((span.startUs - min) / total) * 100,
    widthPct: Math.max(((span.durationUs || 0) / total) * 100, 0.6),
  }));
}
