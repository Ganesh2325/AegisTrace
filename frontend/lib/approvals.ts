export type ApprovalStatus = "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED" | "CANCELLED";
export type ApprovalDecision = "APPROVE" | "REJECT";
export type PolicyDecision = "ALLOW" | "DENY" | "REQUIRE_APPROVAL" | "REQUIRE_ADMIN_APPROVAL" | string;

export type ApprovalSummary = {
  pending: number;
  approvedToday: number;
  rejectedToday: number;
  expired: number;
  cancelled: number;
};

export type ApprovalListItem = {
  id: string;
  status: ApprovalStatus | string;
  tool: string;
  classification: string | null;
  risk: string;
  policyDecision: string | null;
  proposalReason: string | null;
  requiredRole: string;
  requestedAt: string;
  decidedAt: string | null;
  expiresAt: string;
  runId: string;
  proposalId: string;
  requesterId: string;
  requesterEmail: string;
  reviewerId: string | null;
  agentName: string;
  agentVersion: number | null;
  selfRequested: boolean;
  canDecide: boolean;
};

export type ApprovalCitation = {
  documentTitle?: string;
  section?: string;
  quote?: string;
  score?: number;
  chunkId?: string;
};

export type ApprovalTimelineEvent = {
  sequence: number;
  eventType: string;
  state: string;
  payload: Record<string, unknown>;
  createdAt: string;
};

export type ApprovalDetail = ApprovalListItem & {
  decisionReason: string | null;
  reviewerEmail: string | null;
  policyCode: string | null;
  policyReason: string | null;
  runState: string;
  traceId: string;
  agentId: string;
  agentVersionId: string;
  knowledgeName: string | null;
  knowledgeVersion: number | null;
  knowledgeBaseVersionId: string | null;
  arguments: Record<string, unknown> | null;
  includeArguments: boolean;
  citations: ApprovalCitation[];
  executionStatus: string | null;
  executionError: string | null;
  ticketId: string | null;
  whatWillHappen: string;
  timeline: ApprovalTimelineEvent[];
};

export type ApprovalPage = {
  items: ApprovalListItem[];
  page: number;
  size: number;
  total: number;
  summary: ApprovalSummary;
  ordering: string;
};

export const APPROVAL_STATUSES: ApprovalStatus[] = ["PENDING", "APPROVED", "REJECTED", "EXPIRED", "CANCELLED"];
export const APPROVAL_RISKS = ["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const;
export const LIST_POLL_MS = 15_000;
export const DETAIL_POLL_MS = 5_000;

export function needsApproveConfirmation(risk: string, classification: string | null): boolean {
  if (classification === "WRITE") return risk !== "LOW";
  return risk === "HIGH" || risk === "CRITICAL";
}

export function toolLabel(tool: string): string {
  if (tool === "create_support_ticket") return "Create support ticket";
  return tool.replaceAll("_", " ");
}

export function decisionMessage(status: string, code?: string): string {
  if (code === "APPROVAL_EXPIRED") return "Approval no longer pending. Status: Expired.";
  if (code === "APPROVAL_ALREADY_APPROVED") return "Approval already decided. Status: Approved.";
  if (code === "APPROVAL_ALREADY_REJECTED") return "Approval already decided. Status: Rejected.";
  if (code === "APPROVAL_CANCELLED") return "Approval no longer pending. Status: Cancelled.";
  if (code === "FORBIDDEN") return "Not authorized to decide this approval.";
  if (code === "CONFLICT") return "The run is no longer executable.";
  if (status === "APPROVED") return "Approved. The queued tool may execute if the run remains executable.";
  if (status === "REJECTED") return "Rejected. The tool will not execute.";
  return "The approval could not be updated.";
}
