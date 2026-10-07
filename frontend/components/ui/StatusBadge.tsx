import { Icon } from "./Icon";
import { cn } from "./cn";

export const STATUSES = {
  QUEUED: { label: "Queued", tone: "muted", icon: "clock" },
  RUNNING: { label: "Running", tone: "info", icon: "dot" },
  RETRIEVING: { label: "Retrieving", tone: "info", icon: "dot" },
  THINKING: { label: "Thinking", tone: "info", icon: "dot" },
  TOOL_PROPOSED: { label: "Tool proposed", tone: "warning", icon: "alert" },
  APPROVAL_REQUIRED: { label: "Approval required", tone: "warning", icon: "alert" },
  APPROVED: { label: "Approved", tone: "success", icon: "check" },
  REJECTED: { label: "Rejected", tone: "danger", icon: "x" },
  TOOL_EXECUTING: { label: "Executing", tone: "info", icon: "dot" },
  COMPLETED: { label: "Completed", tone: "success", icon: "check" },
  FAILED: { label: "Failed", tone: "danger", icon: "x" },
  CANCELLED: { label: "Cancelled", tone: "muted", icon: "x" },
  TIMED_OUT: { label: "Timed out", tone: "warning", icon: "clock" },
  PENDING: { label: "Pending", tone: "warning", icon: "clock" },
  EXPIRED: { label: "Expired", tone: "warning", icon: "clock" },
  ACTIVE: { label: "Active", tone: "success", icon: "check" },
  INACTIVE: { label: "Inactive", tone: "muted", icon: "dot" },
  DENIED: { label: "Denied", tone: "danger", icon: "x" },
  NO_DATA: { label: "No data", tone: "muted", icon: "alert" },
  UNAVAILABLE: { label: "Unavailable", tone: "warning", icon: "alert" },
  PROCESSING: { label: "Processing", tone: "info", icon: "dot" },
  UPLOADED: { label: "Uploaded", tone: "muted", icon: "clock" },
  DISABLED: { label: "Disabled", tone: "muted", icon: "dot" },
  EMBEDDED: { label: "Embedded", tone: "success", icon: "check" },
  NOT_EMBEDDED: { label: "Not embedded", tone: "muted", icon: "dot" },
  EMBEDDING: { label: "Embedding", tone: "info", icon: "dot" },
  PASS: { label: "Pass", tone: "success", icon: "check" },
  FAIL: { label: "Fail", tone: "danger", icon: "x" },
  ERROR: { label: "Error", tone: "danger", icon: "alert" },
  SKIPPED: { label: "Skipped", tone: "muted", icon: "dot" },
  INCONCLUSIVE: { label: "Inconclusive", tone: "warning", icon: "alert" },
  PARTIAL: { label: "Partial", tone: "warning", icon: "alert" },
  DETECTED: { label: "Detected", tone: "warning", icon: "alert" },
  BLOCKED: { label: "Blocked", tone: "success", icon: "check" },
  ABSTAINED: { label: "Abstained", tone: "info", icon: "dot" },
  UNKNOWN: { label: "Unknown", tone: "muted", icon: "alert" },
} as const;

export type StatusName = keyof typeof STATUSES;

const tones = {
  muted: "bg-white/5 text-muted",
  info: "bg-info/10 text-info",
  success: "bg-success/10 text-success",
  warning: "bg-warning/15 text-warning",
  danger: "bg-danger/10 text-danger",
} as const;

export function StatusBadge({ status, label }: { status: string; label?: string }) {
  const known = status in STATUSES ? STATUSES[status as StatusName] : null;
  const text = label || known?.label || status;
  const tone = known?.tone || "muted";
  const icon = known?.icon || "dot";
  return (
    <span className={cn("inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium", tones[tone])}>
      <Icon name={icon} />
      <span>{text}</span>
    </span>
  );
}
