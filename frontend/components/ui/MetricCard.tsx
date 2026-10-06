import type { ReactNode } from "react";
import { HelpTooltip } from "./HelpTooltip";
import { cn } from "./cn";

export function MetricCard({
  label,
  value,
  context,
  hint,
  note,
  tone = "default",
}: {
  label: string;
  value: ReactNode;
  context?: string;
  hint?: string;
  note?: string;
  tone?: "default" | "warning";
}) {
  return (
    <article className={cn("flex h-full min-h-[7.25rem] flex-col rounded-md border px-4 py-3", tone === "warning" ? "border-warning/50 bg-warning/10" : "border-line bg-elevated")}>
      <div className="flex items-center gap-1 text-[11px] font-medium uppercase tracking-[0.14em] text-muted">
        <span>{label}</span>
        {hint && <HelpTooltip text={hint} />}
      </div>
      <div className="mt-2 font-mono text-[1.65rem] leading-none tracking-tight text-paper">{value}</div>
      {context && <p className="mt-2 text-xs leading-4 text-muted">{context}</p>}
      {note && <p className="mt-1 text-xs text-warning">{note}</p>}
    </article>
  );
}
