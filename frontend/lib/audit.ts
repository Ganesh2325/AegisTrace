export type AuditSummaryItem = {
  id: string;
  createdAt: string;
  actorId: string | null;
  actorEmail: string | null;
  actorKind: string;
  role: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  result: string;
  runId: string | null;
  traceId: string | null;
  workspaceId: string | null;
  approvalId: string | null;
};

export type AuditDetail = AuditSummaryItem & {
  requestId?: string | null;
  metadata?: Record<string, unknown>;
  reason?: unknown;
};

export type AuditPage = {
  items: AuditSummaryItem[];
  page: number;
  size: number;
  total: number;
  window: string;
  summary: {
    today: number;
    approvalEvents: number;
    configurationChanges: number;
    policyDecisions: number;
  };
};

export const AUDIT_WINDOWS = ["1H", "24H", "7D", "30D", "ALL"] as const;

export function actorLabel(item: Pick<AuditSummaryItem, "actorKind" | "actorEmail" | "actorId">) {
  if (item.actorKind === "SYSTEM") return "System";
  return item.actorEmail || item.actorId || "Human";
}
