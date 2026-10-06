"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { ApiError, api } from "../../../../../lib/api";
import { canAccess } from "../../../../../lib/access";
import { roleOf } from "../../../../../lib/api";
import { readSession } from "../../../../../lib/session";
import { formatDuration } from "../../../../../lib/duration";
import { compactId, waterfallRows, type TraceDetail, type TraceSpan } from "../../../../../lib/observability";
import { Button, ButtonLink } from "../../../../../components/ui/Button";
import { Card } from "../../../../../components/ui/Card";
import { Drawer } from "../../../../../components/ui/Overlay";
import { Page, PageHeader, SectionHeader } from "../../../../../components/ui/PageHeader";
import { EmptyState, ErrorState, Skeleton, UnavailableState } from "../../../../../components/ui/States";
import { StatusBadge } from "../../../../../components/ui/StatusBadge";
import { CodeBlock, Mono, TextLink, Timestamp } from "../../../../../components/ui/Type";
import { ObservabilityNav } from "../../ObservabilityNav";

export default function TraceDetailPage() {
  const params = useParams<{ traceId: string }>();
  const traceId = decodeURIComponent(params.traceId);
  const role = roleOf(readSession().current);
  const canAudit = canAccess(role, "audit.read");
  const [row, setRow] = useState<TraceDetail | null>(null);
  const [error, setError] = useState("");
  const [status, setStatus] = useState<number | null>(null);
  const [span, setSpan] = useState<TraceSpan | null>(null);

  const load = useCallback(() => {
    api<TraceDetail>(`/api/v1/observability/traces/${encodeURIComponent(traceId)}`)
      .then((body) => { setRow(body); setError(""); setStatus(null); })
      .catch((err) => {
        setStatus(err instanceof ApiError ? err.status : 500);
        setError(err instanceof Error ? err.message : "Unable to load this trace");
        setRow(null);
      });
  }, [traceId]);

  useEffect(() => { load(); }, [load]);

  if (error && !row) {
    return (
      <Page width="wide">
        <PageHeader eyebrow="Observability / Trace" title={compactId(traceId, 12)} />
        <ObservabilityNav />
        <ErrorState title={status === 404 ? "Trace not found" : "Unable to load this trace"} onRetry={load}>{error}</ErrorState>
      </Page>
    );
  }
  if (!row) return <Page width="wide"><Skeleton className="h-40" /></Page>;

  const bars = waterfallRows(row.spans);
  const telemetryDown = row.telemetry.status === "UNAVAILABLE" || row.telemetry.status === "NOT_CONFIGURED";

  return (
    <Page width="wide">
      <PageHeader
        eyebrow={`Observability / Trace ${compactId(row.traceId, 10)}`}
        title={row.agentName}
        description={`aegistrace.agent.run · version used v${row.agentVersion}`}
        status={<StatusBadge status={row.status} />}
        actions={
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" onClick={load}>Refresh</Button>
            {row.jaegerUrl && <TextLink href={row.jaegerUrl} external>Open in Jaeger</TextLink>}
          </div>
        }
      />
      <ObservabilityNav />
      {telemetryDown && (
        <UnavailableState title="Trace backend unavailable">
          {row.telemetry.message || "Jaeger spans are not available. Product events below still describe the run lifecycle."}
        </UnavailableState>
      )}
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,0.8fr)]">
        <Card>
          <SectionHeader title="Telemetry waterfall" description="OpenTelemetry spans from Jaeger. These are not product events." />
          {bars.length === 0 ? (
            <p className="mt-3 text-sm text-muted">No spans stored for this identifier.</p>
          ) : (
            <>
              <ul className="mt-3 hidden space-y-2 md:block">
                {bars.map((item) => (
                  <li key={item.spanId}>
                    <button type="button" className="w-full rounded-md border border-line px-2 py-2 text-left hover:bg-white/[0.03] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent" onClick={() => setSpan(item)} onKeyDown={(event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); setSpan(item); } }}>
                      <div className="flex items-center justify-between gap-2 text-xs">
                        <span className="min-w-0 truncate" style={{ paddingLeft: Math.min(item.depth, 6) * 12 }}>{item.name}</span>
                        <span className="text-muted">{formatDuration(item.durationMs)}</span>
                      </div>
                      <div className="relative mt-1 h-2 rounded bg-canvas">
                        <span className={`absolute h-2 rounded ${item.status === "ERROR" ? "bg-danger" : "bg-info"}`} style={{ left: `${item.offsetPct}%`, width: `${item.widthPct}%` }} />
                      </div>
                      <p className="mt-1 text-[11px] text-muted">{item.service} · {item.status}</p>
                    </button>
                  </li>
                ))}
              </ul>
              <ul className="mt-3 space-y-2 md:hidden">
                {bars.map((item) => (
                  <li key={item.spanId}>
                    <button type="button" className="w-full rounded-md border border-line px-3 py-2 text-left" onClick={() => setSpan(item)}>
                      <div className="flex items-center justify-between">
                        <span className="min-w-0 truncate text-sm" style={{ paddingLeft: Math.min(item.depth, 4) * 10 }}>{item.name}</span>
                        <StatusBadge status={item.status} />
                      </div>
                      <p className="text-xs text-muted">{item.service} · {formatDuration(item.durationMs)}</p>
                    </button>
                  </li>
                ))}
              </ul>
            </>
          )}
        </Card>
        <div className="space-y-4">
          <Card>
            <SectionHeader title="Correlation" />
            <dl className="mt-3 space-y-2 text-sm">
              <div><dt className="text-xs text-muted">Trace</dt><dd><Mono>{row.traceId}</Mono></dd></div>
              <div><dt className="text-xs text-muted">Run</dt><dd><ButtonLink href={`/runs/${row.runId}`} variant="ghost"><Mono>{row.runId}</Mono></ButtonLink></dd></div>
              <div><dt className="text-xs text-muted">Duration</dt><dd>{row.durationMs == null ? "—" : formatDuration(row.durationMs)}</dd></div>
              <div><dt className="text-xs text-muted">Agent version used</dt><dd>v{row.agentVersion} · <Mono>{compactId(row.agentVersionId)}</Mono></dd></div>
              {row.knowledgeVersion != null && <div><dt className="text-xs text-muted">Knowledge version used</dt><dd>v{row.knowledgeVersion}</dd></div>}
              {row.approvalId && <div><dt className="text-xs text-muted">Approval</dt><dd><ButtonLink href={`/approvals/${row.approvalId}`} variant="ghost"><Mono>{compactId(row.approvalId)}</Mono></ButtonLink></dd></div>}
              {row.toolProposalId && <div><dt className="text-xs text-muted">Tool proposal</dt><dd><Mono>{compactId(row.toolProposalId)}</Mono></dd></div>}
              {canAudit && <div><dt className="text-xs text-muted">Audit</dt><dd><ButtonLink href={`/audit?runId=${row.runId}`} variant="ghost">Events for this run</ButtonLink></dd></div>}
              {row.failureCategory && <div><dt className="text-xs text-muted">Error</dt><dd>{row.failureCategory}{row.errorCode ? ` · ${row.errorCode}` : ""}</dd></div>}
            </dl>
          </Card>
          <Card>
            <SectionHeader title="Product timing" description="Derived from run_events. Not OpenTelemetry spans." />
            {row.productPhases.length === 0 ? (
              <p className="mt-3 text-sm text-muted">Not enough paired events to show phase timings.</p>
            ) : (
              <ul className="mt-3 space-y-1 text-sm">
                {row.productPhases.map((phase) => (
                  <li key={phase.name + (phase.start || "")} className="flex justify-between gap-2">
                    <span><span className="text-[10px] uppercase tracking-[0.12em] text-muted">Product event</span> {phase.name}</span>
                    <span>{phase.durationMs == null ? "—" : formatDuration(phase.durationMs)}</span>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>
      </div>
      <Card>
        <SectionHeader title="Product timeline" description="Business lifecycle. Distinct from telemetry spans and audit events." />
        {row.productEvents.length === 0 ? (
          <EmptyState title="No product events">The run has no stored timeline yet.</EmptyState>
        ) : (
          <ol className="mt-3 space-y-2">
            {row.productEvents.map((event) => (
              <li key={event.sequence} className="flex flex-wrap items-center justify-between gap-2 text-sm">
                <span><span className="mr-2 text-[10px] uppercase tracking-[0.12em] text-muted">Product event</span>{event.name}</span>
                <Timestamp value={event.at} />
              </li>
            ))}
          </ol>
        )}
      </Card>
      <Drawer open={!!span} title={span?.name || "Span"} onClose={() => setSpan(null)}>
        {span && (
          <dl className="space-y-3 text-sm">
            <div><dt className="text-xs text-muted">Kind</dt><dd>TELEMETRY</dd></div>
            <div><dt className="text-xs text-muted">Service</dt><dd>{span.service}</dd></div>
            <div><dt className="text-xs text-muted">Status</dt><dd><StatusBadge status={span.status} /></dd></div>
            <div><dt className="text-xs text-muted">Duration</dt><dd>{formatDuration(span.durationMs)}</dd></div>
            <div><dt className="text-xs text-muted">Trace</dt><dd><Mono>{row.traceId}</Mono></dd></div>
            <div><dt className="text-xs text-muted">Run</dt><dd><Mono>{row.runId}</Mono></dd></div>
            {span.status === "ERROR" && <p className="text-sm text-danger">This span recorded an error. Stack traces are withheld.</p>}
            <div>
              <dt className="text-xs text-muted">Attributes</dt>
              <dd className="mt-1"><CodeBlock value={JSON.stringify(span.attributes, null, 2)} /></dd>
            </div>
          </dl>
        )}
      </Drawer>
    </Page>
  );
}
