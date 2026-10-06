"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { api } from "../../../../lib/api";
import { actorLabel, type AuditDetail } from "../../../../lib/audit";
import { compactId } from "../../../../lib/observability";
import { ButtonLink } from "../../../../components/ui/Button";
import { Card } from "../../../../components/ui/Card";
import { Page, PageHeader, SectionHeader } from "../../../../components/ui/PageHeader";
import { ErrorState, Skeleton } from "../../../../components/ui/States";
import { StatusBadge } from "../../../../components/ui/StatusBadge";
import { CodeBlock, Mono, Timestamp } from "../../../../components/ui/Type";

export default function AuditDetailPage() {
  const params = useParams<{ id: string }>();
  const [row, setRow] = useState<AuditDetail | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(() => {
    api<AuditDetail>(`/api/v1/audit/${params.id}`)
      .then((body) => { setRow(body); setError(""); })
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load this event"));
  }, [params.id]);

  useEffect(() => { load(); }, [load]);

  if (error && !row) {
    return (
      <Page width="wide">
        <PageHeader eyebrow="Audit / Event" title="Event" />
        <ErrorState title={error.toLowerCase().includes("not found") ? "Audit event not found" : "Unable to load this event"} onRetry={load}>{error}</ErrorState>
      </Page>
    );
  }
  if (!row) return <Page width="wide"><Skeleton className="h-40" /></Page>;

  return (
    <Page width="wide">
      <PageHeader
        eyebrow={`Audit / Event ${compactId(row.id)}`}
        title={row.action}
        status={<StatusBadge status={row.result} />}
        actions={<ButtonLink href="/audit" variant="ghost">Back to audit</ButtonLink>}
      />
      <Card>
        <SectionHeader title="Record" description="Append-only. There is no edit or delete." />
        <dl className="mt-3 grid gap-3 text-sm sm:grid-cols-2">
          <div><dt className="text-xs text-muted">Event ID</dt><dd><Mono>{row.id}</Mono></dd></div>
          <div><dt className="text-xs text-muted">Timestamp</dt><dd><Timestamp value={row.createdAt} /></dd></div>
          <div><dt className="text-xs text-muted">Actor</dt><dd>{actorLabel(row)} ({row.actorKind.toLowerCase()})</dd></div>
          <div><dt className="text-xs text-muted">Role</dt><dd>{row.role || "—"}</dd></div>
          <div><dt className="text-xs text-muted">Workspace</dt><dd><Mono>{row.workspaceId}</Mono></dd></div>
          <div><dt className="text-xs text-muted">Action</dt><dd>{row.action}</dd></div>
          <div><dt className="text-xs text-muted">Resource</dt><dd><Mono>{row.resourceType} {row.resourceId}</Mono></dd></div>
          <div><dt className="text-xs text-muted">Result</dt><dd>{row.result}</dd></div>
          {row.reason != null && row.reason !== "" && <div className="sm:col-span-2"><dt className="text-xs text-muted">Reason</dt><dd>{String(row.reason)}</dd></div>}
          {row.runId && <div><dt className="text-xs text-muted">Run</dt><dd><ButtonLink href={`/runs/${row.runId}`} variant="ghost"><Mono>{row.runId}</Mono></ButtonLink></dd></div>}
          {row.traceId && <div><dt className="text-xs text-muted">Trace</dt><dd><ButtonLink href={`/observability/traces/${encodeURIComponent(row.traceId)}`} variant="ghost"><Mono>{row.traceId}</Mono></ButtonLink></dd></div>}
          {row.approvalId && <div><dt className="text-xs text-muted">Approval</dt><dd><ButtonLink href={`/approvals/${row.approvalId}`} variant="ghost"><Mono>{row.approvalId}</Mono></ButtonLink></dd></div>}
        </dl>
      </Card>
      <Card>
        <SectionHeader title="Metadata" description="Sanitized fields persisted with the event." />
        <div className="mt-3"><CodeBlock value={JSON.stringify(row.metadata || {}, null, 2)} /></div>
      </Card>
    </Page>
  );
}
