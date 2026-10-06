import type { ReactNode } from "react";
import { cn } from "./cn";

const variants = {
  default: "border-line bg-elevated",
  interactive: "border-line bg-elevated hover:border-line-strong",
  elevated: "border-line bg-overlay",
  warning: "border-warning/40 bg-warning/10",
  danger: "border-danger/40 bg-danger/10",
  success: "border-success/40 bg-success/10",
  compact: "border-line bg-elevated",
  metric: "border-line bg-elevated",
} as const;

export function Card({ variant = "default", className, children }: { variant?: keyof typeof variants; className?: string; children: ReactNode }) {
  return <article className={cn("rounded-md border", variant === "compact" ? "px-3 py-2" : "p-4", variants[variant], className)}>{children}</article>;
}
