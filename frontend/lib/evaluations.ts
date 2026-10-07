export type MetricRate = {
  value: number | null;
  status: "OK" | "NO_DATA";
  numerator: number;
  denominator: number;
  definition: string;
};

export type EvaluationRun = {
  id: string;
  status: string;
  suiteName: string;
  suiteVersion: number;
  agentVersion: number;
  knowledgeVersion: number;
  provider: string;
  model: string;
  evaluatorVersion: string;
  traceId?: string | null;
  createdAt: string;
  startedAt?: string | null;
  completedAt?: string | null;
  totalCases: number;
  completedCases: number;
  passes: number;
  failures: number;
};

export type EvaluationOverview = {
  generatedAt: string;
  executions: number;
  active: number;
  completed: number;
  passRate: MetricRate;
  failureRate: MetricRate;
  groundingPassRate: MetricRate;
  citationPassRate: MetricRate;
  policyComplianceRate: MetricRate;
  abstentionCorrectness: MetricRate;
  promptInjectionResistance: MetricRate;
  regressions: number;
  recent: EvaluationRun[];
};

export type EvaluationCase = {
  id: string;
  key: string;
  version: number;
  name: string;
  description: string;
  category: string;
  executionType: string;
  input: string;
  expectations: Record<string, unknown>;
  enabled: boolean;
  fixtureSource?: string | null;
  createdAt: string;
};

export type EvaluationSuite = {
  id: string;
  key: string;
  version: number;
  name: string;
  description: string;
  enabled: boolean;
  fixtureSource?: string | null;
  caseCount: number;
  createdAt: string;
};

export function rateText(metric: MetricRate): string {
  if (metric.status !== "OK" || metric.value == null) return "No data";
  return `${Math.round(metric.value * 1000) / 10}%`;
}

export function progressText(run: Pick<EvaluationRun, "completedCases" | "totalCases">): string {
  return `${run.completedCases} of ${run.totalCases}`;
}

export function isExecutionActive(status: string): boolean {
  return status === "QUEUED" || status === "RUNNING";
}
