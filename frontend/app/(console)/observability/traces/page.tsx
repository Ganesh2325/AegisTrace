"use client";

import Link from "next/link";
import { FormEvent, Suspense, useCallback, useEffect, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { api } from "../../../../lib/api";
import { formatDuration } from "../../../../lib/duration";
import { OBS_WINDOWS, compactId, parseObsWindow, type ObsWindow, type TraceListItem } from "../../../../lib/observability";
import { Button } from "../../../../components/ui/Button";
import { DataTable } from "../../../../components/ui/DataTable";
import { TextField } from "../../../../components/ui/Field";
import { Page, PageHeader } from "../../../../components/ui/PageHeader";
import { EmptyState, ErrorState, Skeleton, UnavailableState } from "../../../../components/ui/States";
import { StatusBadge } from "../../../../components/ui/StatusBadge";
import { Mono, Timestamp } from "../../../../components/ui/Type";
import { ObservabilityNav } from "../ObservabilityNav";

type PageBody = {
  items: TraceListItem[];
  page: number;
  size: number;
  total: number;
  telemetry: { traces: string };
};

export default function TracesPage() {
  return (
    <Suspense fallback={<Page width="wide"><Skeleton className="h-32" /></Page>}>
      <Explorer />
    </Suspense>
  );
}

function Explorer() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const window = parseObsWindow(params.get("range"));
  const status = params.get("status") || "ALL";
  const runId = params.get("runId") || "";
  const traceId = params.get("traceId") || "";
  const agent = params.get("agent") || "";
  const page = Math.max(Number(params.get("page") || "0") || 0, 0);
  const [data, setData] = useState<PageBody | null>(null);
  const [error, setError] = useState("");
  const [ready, setReady] = useState(false);

  const load = useCallback(() => {
    const query = new URLSearchParams({ window, status, page: String(page) });
    if (runId) query.set("runId", runId);
    if (traceId) query.set("traceId", traceId);
    if (agent) query.set("agent", agent);
    api<PageBody>(`/api/v1/observability/traces?${query}`)
      .then((row) => { setData(row); setError(""); })
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load traces"))
      .finally(() => setReady(true));
  }, [window, status, runId, traceId, agent, page]);

  useEffect(() => { load(); }, [load]);

  function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const search = new URLSearchParams();
    search.set("range", String(form.get("range") || window));
    search.set("status", String(form.get("status") || "ALL"));
    const nextRun = String(form.get("runId") || "").trim();
    const nextTrace = String(form.get("traceId") || "").trim();
    const nextAgent = String(form.get("agent") || "").trim();
    if (nextRun) search.set("runId", nextRun);
    if (nextTrace) search.set("traceId", nextTrace);
    if (nextAgent) search.set("agent", nextAgent);
    search.set("page", "0");
    router.replace(`${pathname}?${search.toString()}`);
  }

  const tracesDown = data?.telemetry.traces === "UNAVAILABLE" || data?.telemetry.traces === "NOT_CONFIGURED";

  return (
    <Page width="wide">
      <PageHeader eyebrow="Observability" title="Trace explorer" description="Rows are workspace runs with a stored trace id. Open a row to load spans. The list does not prefetch waterfalls." />
      <ObservabilityNav />
      {tracesDown && (
        <UnavailableState title="Trace backend unavailable">
          Product-level rows may still appear from Postgres. Span waterfalls need Jaeger.
        </UnavailableState>
      )}
      <form onSubmit={apply} className="grid gap-3 md:grid-cols-5">
        <label className="text-sm">
          Range
          <select name="range" defaultValue={window} className="mt-1 w-full rounded-md border border-line bg-canvas px-3 py-2 text-sm">
            {OBS_WINDOWS.map((item) => <option key={item} value={item}>{item}</option>)}
          </select>
        </label>
        <label className="text-sm">
          Status
          <select name="status" defaultValue={status} className="mt-1 w-full rounded-md border border-line bg-canvas px-3 py-2 text-sm">
            {["ALL", "COMPLETED", "FAILED", "TIMED_OUT", "CANCELLED", "APPROVAL_REQUIRED"].map((item) => <option key={item} value={item}>{item}</option>)}
          </select>
        </label>
        <TextField name="runId" label="Run ID" defaultValue={runId} />
        <TextField name="traceId" label="Trace ID" defaultValue={traceId} />
        <TextField name="agent" label="Agent" defaultValue={agent} />
        <div className="md:col-span-5"><Button type="submit" variant="secondary">Filter</Button></div>
      </form>
      {error && <ErrorState title="Unable to load traces" onRetry={load}>{error}</ErrorState>}
      {!ready && !error && <Skeleton className="h-32" />}
      {data && (
        <>
          <div className="hidden md:block">
            <DataTable
              rows={data.items}
              getKey={(row) => row.traceId + row.runId}
              empty={<EmptyState title="No traces available">Run an agent execution to generate trace data.</EmptyState>}
              columns={[
                { key: "trace", header: "Trace ID", cell: (row) => <Link className="text-info underline-offset-2 hover:underline" href={`/observability/traces/${encodeURIComponent(row.traceId)}`}><Mono>{compactId(row.traceId, 12)}</Mono></Link> },
                { key: "run", header: "Run ID", cell: (row) => <Link className="text-info underline-offset-2 hover:underline" href={`/runs/${row.runId}`}><Mono>{compactId(row.runId)}</Mono></Link> },
                { key: "op", header: "Operation", cell: (row) => row.operation },
                { key: "status", header: "Status", cell: (row) => <StatusBadge status={row.status} /> },
                { key: "dur", header: "Duration", cell: (row) => row.durationMs == null ? "—" : formatDuration(row.durationMs) },
                { key: "agent", header: "Agent", cell: (row) => `${row.agent}${row.agentVersion != null ? ` v${row.agentVersion}` : ""}` },
                { key: "started", header: "Started", cell: (row) => <Timestamp value={row.startedAt} /> },
                { key: "env", header: "Environment", cell: (row) => row.environment },
              ]}
              pagination={<Pager page={data.page} size={data.size} total={data.total} onPage={(next) => {
                const search = new URLSearchParams(params.toString());
                search.set("page", String(next));
                router.replace(`${pathname}?${search.toString()}`);
              }} />}
            />
          </div>
          <ul className="space-y-2 md:hidden">
            {data.items.length === 0 && <EmptyState title="No traces available">Run an agent execution to generate trace data.</EmptyState>}
            {data.items.map((row) => (
              <li key={row.traceId + row.runId}>
                <Link href={`/observability/traces/${encodeURIComponent(row.traceId)}`} className="block rounded-md border border-line px-3 py-2">
                  <div className="flex items-center justify-between gap-2">
                    <Mono>{compactId(row.traceId, 10)}</Mono>
                    <StatusBadge status={row.status} />
                  </div>
                  <p className="mt-1 text-sm">{row.agent}</p>
                  <p className="text-xs text-muted">{row.durationMs == null ? "—" : formatDuration(row.durationMs)}</p>
                </Link>
              </li>
            ))}
          </ul>
        </>
      )}
    </Page>
  );
}

function Pager({ page, size, total, onPage }: { page: number; size: number; total: number; onPage: (page: number) => void }) {
  const last = Math.max(Math.ceil(total / size) - 1, 0);
  return (
    <div className="flex items-center justify-between">
      <span>{total} traces</span>
      <div className="flex gap-2">
        <Button type="button" variant="ghost" disabled={page <= 0} onClick={() => onPage(page - 1)}>Previous</Button>
        <Button type="button" variant="ghost" disabled={page >= last} onClick={() => onPage(page + 1)}>Next</Button>
      </div>
    </div>
  );
}
