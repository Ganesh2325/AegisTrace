import type { ReactNode } from "react";
import { Button, ButtonLink } from "./Button";

export function EmptyState({ title, children, actionHref, actionLabel }: { title: string; children: string; actionHref?: string; actionLabel?: string }) {
  return (
    <div className="rounded-md border border-dashed border-line px-4 py-6">
      <h2 className="text-sm font-medium text-paper">{title}</h2>
      <p className="mt-1 max-w-xl text-sm text-muted">{children}</p>
      {actionHref && actionLabel && <div className="mt-3"><ButtonLink href={actionHref} variant="secondary">{actionLabel}</ButtonLink></div>}
    </div>
  );
}

export function ErrorState({ title, children, onRetry }: { title: string; children: ReactNode; onRetry?: () => void }) {
  return (
    <div className="rounded-md border border-danger/40 bg-danger/10 px-4 py-4" role="alert">
      <h2 className="text-sm font-medium text-paper">{title}</h2>
      <p className="mt-1 text-sm text-muted">{children}</p>
      {onRetry && <div className="mt-3"><Button variant="secondary" onClick={onRetry}>Retry</Button></div>}
    </div>
  );
}

export function UnavailableState({ title, children, onRetry }: { title: string; children: string; onRetry?: () => void }) {
  return (
    <div className="rounded-md border border-warning/40 bg-warning/10 px-4 py-4" role="status">
      <h2 className="text-sm font-medium text-paper">{title}</h2>
      <p className="mt-1 text-sm text-muted">{children}</p>
      {onRetry && <div className="mt-3"><Button variant="secondary" onClick={onRetry}>Retry</Button></div>}
    </div>
  );
}

export function Skeleton({ className = "h-24" }: { className?: string }) {
  return <div className={`animate-pulse rounded-md bg-elevated ${className}`} aria-hidden="true" />;
}

export function ForbiddenState({ capability }: { capability: string }) {
  return (
    <div className="max-w-xl rounded-md border border-warning/40 bg-warning/10 px-4 py-4" role="alert">
      <h1 className="text-base font-semibold text-paper">Access restricted</h1>
      <p className="mt-1 text-sm text-muted">You do not have permission to view this workspace area.</p>
      <p className="mt-2 text-xs text-muted">Required permission: <span className="font-mono text-paper">{capability}</span></p>
      <div className="mt-3"><ButtonLink href="/" variant="secondary">Return to Overview</ButtonLink></div>
    </div>
  );
}

export function PageLoading({ label }: { label: string }) {
  return (
    <div className="space-y-3 p-2" aria-busy="true" aria-live="polite">
      <p className="text-sm text-muted">{label}</p>
      <Skeleton className="h-8 w-48" />
      <Skeleton className="h-32" />
    </div>
  );
}
