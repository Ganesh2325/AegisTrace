"use client";

import Link from "next/link";
import { FormEvent, Suspense, useCallback, useEffect, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { api } from "../../../lib/api";
import { AUDIT_WINDOWS, actorLabel, type AuditPage, type AuditSummaryItem } from "../../../lib/audit";
import { compactId } from "../../../lib/observability";
import { Button } from "../../../components/ui/Button";
import { DataTable } from "../../../components/ui/DataTable";
import { TextField } from "../../../components/ui/Field";
import { MetricCard } from "../../../components/ui/MetricCard";
import { Page, PageHeader } from "../../../components/ui/PageHeader";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/States";
import { StatusBadge } from "../../../components/ui/StatusBadge";
import { Mono, Timestamp } from "../../../components/ui/Type";

export default function AuditPage() {
  return (
    <Suspense fallback={<Page width="wide"><Skeleton className="h-32" /></Page>}>
      <AuditCenter />
    </Suspense>
  );
}

function AuditCenter() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const window = params.get("range") || "7D";
  const page = Math.max(Number(params.get("page") || "0") || 0, 0);
  const [data, setData] = useState<AuditPage | null>(null);
  const [error, setError] = useState("");
  const [ready, setReady] = useState(false);

  const load = useCallback(() => {
    const query = new URLSearchParams();
    query.set("window", window);
    query.set("page", String(page));
    for (const key of ["action", "actor", "resourceType", "result", "runId", "approvalId", "q"]) {
      const value = params.get(key);
      if (value) query.set(key, value);
    }
    api<AuditPage>(`/api/v1/audit?${query}`)
      .then((row) => { setData(row); setError(""); })
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load audit"))
      .finally(() => setReady(true));
  }, [window, page, params]);

  useEffect(() => { load(); }, [load]);

  function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const search = new URLSearchParams();
    search.set("range", String(form.get("range") || "7D"));
    for (const key of ["q", "action", "actor", "resourceType", "result", "runId", "approvalId"]) {
      const value = String(form.get(key) || "").trim();
      if (value) search.set(key, value);
    }
    search.set("page", "0");
    router.replace(`${pathname}?${search.toString()}`);
  }

  return (
    <Page width="wide">
      <PageHeader
        eyebrow="Governance"
        title="Audit Center"
        description="Immutable records of who changed, approved, or decided what. This is not the observability event stream."
      />
      {data && (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          <MetricCard label="Events today" value={data.summary.today} hint="Created since 00:00 UTC in the current filter set." />
          <MetricCard label="Approval events" value={data.summary.approvalEvents} />
          <MetricCard label="Configuration" value={data.summary.configurationChanges} />
          <MetricCard label="Policy decisions" value={data.summary.policyDecisions} />
        </div>
      )}
      <form onSubmit={apply} className="grid gap-3 md:grid-cols-4">
        <label className="text-sm">
          Range
          <select name="range" defaultValue={window} className="mt-1 w-full rounded-md border border-line bg-canvas px-3 py-2 text-sm">
            {AUDIT_WINDOWS.map((item) => <option key={item} value={item}>{item}</option>)}
          </select>
        </label>
        <TextField name="q" label="Search" defaultValue={params.get("q") || ""} hint="Event, run, actor, action, resource, approval, or proposal id" />
        <TextField name="action" label="Action" defaultValue={params.get("action") || ""} />
        <TextField name="actor" label="Actor" defaultValue={params.get("actor") || ""} />
        <TextField name="resourceType" label="Resource" defaultValue={params.get("resourceType") || ""} />
        <label className="text-sm">
          Result
          <select name="result" defaultValue={params.get("result") || ""} className="mt-1 w-full rounded-md border border-line bg-canvas px-3 py-2 text-sm">
            <option value="">Any</option>
            <option value="SUCCESS">SUCCESS</option>
            <option value="DENIED">DENIED</option>
            <option value="FAILED">FAILED</option>
          </select>
        </label>
        <TextField name="runId" label="Run ID" defaultValue={params.get("runId") || ""} />
        <TextField name="approvalId" label="Approval ID" defaultValue={params.get("approvalId") || ""} />
        <div className="md:col-span-4"><Button type="submit" variant="secondary">Filter</Button></div>
      </form>
      {error && <ErrorState title="Unable to load audit" onRetry={load}>{error}</ErrorState>}
      {!ready && !error && <Skeleton className="h-24" />}
      {data && (
        <>
          <div className="hidden md:block">
            <DataTable
              rows={data.items}
              getKey={(item) => item.id}
              empty={<EmptyState title="No audit activity yet">Actions in this workspace will appear here after they are recorded.</EmptyState>}
              columns={[
                { key: "when", header: "Timestamp", cell: (item) => <Timestamp value={item.createdAt} /> },
                { key: "actor", header: "Actor", cell: (item) => actorLabel(item) },
                { key: "role", header: "Role", cell: (item) => item.role || (item.actorKind === "SYSTEM" ? "system" : "—") },
                { key: "action", header: "Action", cell: (item) => <Link className="text-info underline-offset-2 hover:underline" href={`/audit/${item.id}`}>{item.action}</Link> },
                { key: "resource", header: "Resource", cell: (item) => <Mono>{item.resourceType}{item.resourceId ? ` ${compactId(item.resourceId)}` : ""}</Mono> },
                { key: "result", header: "Result", cell: (item) => <StatusBadge status={item.result} /> },
                { key: "run", header: "Run", cell: (item) => item.runId ? <Link className="text-info underline-offset-2 hover:underline" href={`/runs/${item.runId}`}><Mono>{compactId(item.runId)}</Mono></Link> : "—" },
                { key: "trace", header: "Trace", cell: (item) => item.traceId ? <Link className="text-info underline-offset-2 hover:underline" href={`/observability/traces/${encodeURIComponent(item.traceId)}`}><Mono>{compactId(item.traceId, 10)}</Mono></Link> : "—" },
              ]}
              pagination={<span>{data.total} events · page {data.page + 1}</span>}
            />
          </div>
          <ul className="space-y-2 md:hidden">
            {data.items.length === 0 && <EmptyState title="No audit activity yet">Actions in this workspace will appear here after they are recorded.</EmptyState>}
            {data.items.map((item) => <AuditCard key={item.id} item={item} />)}
          </ul>
          <div className="flex gap-2">
            <Button variant="ghost" disabled={page <= 0} onClick={() => {
              const search = new URLSearchParams(params.toString());
              search.set("page", String(page - 1));
              router.replace(`${pathname}?${search}`);
            }}>Previous</Button>
            <Button variant="ghost" disabled={(page + 1) * data.size >= data.total} onClick={() => {
              const search = new URLSearchParams(params.toString());
              search.set("page", String(page + 1));
              router.replace(`${pathname}?${search}`);
            }}>Next</Button>
          </div>
        </>
      )}
    </Page>
  );
}

function AuditCard({ item }: { item: AuditSummaryItem }) {
  return (
    <li>
      <Link href={`/audit/${item.id}`} className="block rounded-md border border-line px-3 py-2">
        <div className="flex items-center justify-between gap-2">
          <span className="text-sm">{item.action}</span>
          <StatusBadge status={item.result} />
        </div>
        <p className="mt-1 text-xs text-muted">{actorLabel(item)} · {item.resourceType}</p>
        <p className="text-xs text-muted"><Timestamp value={item.createdAt} /></p>
      </Link>
    </li>
  );
}
