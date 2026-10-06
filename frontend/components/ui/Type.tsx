import type { ReactNode } from "react";
import { formatDuration, formatExactMs } from "../../lib/duration";

export function DurationText({ ms, className }: { ms: number | null | undefined; className?: string }) {
  if (ms == null || !Number.isFinite(ms)) return <span className={className}>No data</span>;
  return <span title={formatExactMs(ms)} className={className}>{formatDuration(ms)}</span>;
}

export function Timestamp({ value }: { value: string }) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return <span className="font-mono text-xs text-muted">{value}</span>;
  const exact = `${date.toISOString().replace("T", " ").replace(/\.\d+Z$/, " UTC")}`;
  return (
    <time dateTime={date.toISOString()} title={exact} className="font-mono text-xs text-muted">
      {relative(date)}
    </time>
  );
}

function relative(date: Date) {
  const seconds = Math.round((date.getTime() - Date.now()) / 1000);
  const abs = Math.abs(seconds);
  const formatter = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
  if (abs < 60) return formatter.format(seconds, "second");
  if (abs < 3600) return formatter.format(Math.round(seconds / 60), "minute");
  if (abs < 86400) return formatter.format(Math.round(seconds / 3600), "hour");
  return formatter.format(Math.round(seconds / 86400), "day");
}

export function Mono({ children }: { children: ReactNode }) {
  return <span className="font-mono text-xs text-muted">{children}</span>;
}

export function CodeBlock({ value }: { value: string }) {
  return <pre className="overflow-auto rounded-md border border-line bg-canvas p-3 font-mono text-xs leading-5 text-paper">{value}</pre>;
}

const linkVariants = {
  primary: "text-paper underline-offset-2 hover:underline",
  secondary: "text-muted underline-offset-2 hover:text-paper hover:underline",
  inline: "text-info underline-offset-2 hover:underline",
  external: "text-info underline-offset-2 hover:underline",
  nav: "rounded-md px-2 py-1.5 text-sm text-muted hover:bg-white/5 hover:text-paper",
} as const;

export function TextLink({
  href,
  children,
  external,
  variant = "inline",
}: {
  href: string;
  children: ReactNode;
  external?: boolean;
  variant?: keyof typeof linkVariants;
}) {
  const outbound = external || variant === "external";
  return (
    <a href={href} className={linkVariants[outbound ? "external" : variant]} {...(outbound ? { target: "_blank", rel: "noreferrer" } : {})}>
      {children}
      {outbound ? " ↗" : ""}
    </a>
  );
}
