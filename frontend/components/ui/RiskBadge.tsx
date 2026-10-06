import { cn } from "./cn";

const LEVELS = {
  LOW: { label: "Low risk", meaning: "Read-only or low-impact.", tone: "bg-success/10 text-success" },
  MEDIUM: { label: "Medium risk", meaning: "Needs a recorded decision.", tone: "bg-warning/15 text-warning" },
  HIGH: { label: "High risk", meaning: "Needs an administrator.", tone: "bg-danger/10 text-danger" },
  CRITICAL: { label: "Critical risk", meaning: "Blocked by policy.", tone: "bg-danger/20 text-danger" },
} as const;

export type RiskLevel = keyof typeof LEVELS;

export function isRiskLevel(value: string): value is RiskLevel {
  return value in LEVELS;
}

export function RiskBadge({ level }: { level: string }) {
  if (!isRiskLevel(level)) {
    return <span className="text-xs text-muted">Risk {level}</span>;
  }
  const item = LEVELS[level];
  return (
    <span className={cn("inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-medium", item.tone)} title={item.meaning}>
      {item.label}
      <span className="sr-only">. {item.meaning}</span>
    </span>
  );
}
