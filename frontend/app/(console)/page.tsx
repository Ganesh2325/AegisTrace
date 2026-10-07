"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState, Suspense } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { api } from "../../lib/api";
import { formatDuration, formatExactMs } from "../../lib/duration";
import type { OperationsOverview, OperationsRun, OperationsSummary, RunFilter, WindowCode } from "../../lib/operations";
import { RUN_FILTERS, WINDOWS } from "../../lib/operations";
import { Button, ButtonLink } from "../../components/ui/Button";
import { Card } from "../../components/ui/Card";
import { DataTable } from "../../components/ui/DataTable";
import { MetricCard } from "../../components/ui/MetricCard";
import { Page, PageHeader, SectionHeader } from "../../components/ui/PageHeader";
import { SparkBars } from "../../components/ui/SparkBars";
import { EmptyState, ErrorState, Skeleton } from "../../components/ui/States";
import { StatusBadge } from "../../components/ui/StatusBadge";
import { DurationText, Mono, Timestamp } from "../../components/ui/Type";

const WINDOW_LABEL: Record<WindowCode, string> = {
  "24H": "Previous 24 hours",
  "7D": "Previous 168 hours",
  "30D": "Previous 720 hours",
  ALL: "All time",
};

export default function DashboardPage() {
  return (
    <Suspense fallback={<Page width="wide"><Skeleton className="h-32" /></Page>}>
      <Dashboard />
    </Suspense>
  );
}

function Dashboard() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const window = parseWindow(params.get("range"));
  const status = parseFilter(params.get("status"));
  const page = Math.max(Number(params.get("page") || "0") || 0, 0);
  const [overview, setOverview] = useState<OperationsOverview | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const request = useRef<AbortController | null>(null);

  const load = useCallback(async (background: boolean) => {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    if (background) setRefreshing(true);
    else setLoading(true);
    try {
      const query = new URLSearchParams({ window, status, page: String(page) });
      const row = await api<OperationsOverview>(`/api/v1/operations/overview?${query}`, { signal: controller.signal });
      setOverview(row);
      setError("");
    } catch (err) {
      if (!(err instanceof DOMException && err.name === "AbortError")) {
        setError(err instanceof Error ? err.message : "Unable to load operations");
        if (!background) setOverview(null);
      }
    } finally {
      if (request.current === controller) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, [window, status, page]);

  useEffect(() => {
    void load(false);
    return () => request.current?.abort();
  }, [load]);

  function setQuery(next: { range?: WindowCode; status?: RunFilter; page?: number }) {
    const search = new URLSearchParams(params.toString());
    search.set("range", next.range ?? window);
    search.set("status", next.status ?? status);
    search.set("page", String(next.page ?? 0));
    router.replace(`${pathname}?${search.toString()}`);
  }

  const summary = overview?.summary;
  const empty = summary?.runs.value === 0;

  return (
    <Page width="wide">
      <PageHeader
        eyebrow="Workspace"
        title="Operations"
        description={`${WINDOW_LABEL[window]}. UTC rolling hours, or every stored run for All time. Rates use finished runs only. Durations are end-to-end and include approval wait.`}
        status={overview && <span className="text-xs text-muted">Updated {relative(overview.generatedAt)}{refreshing ? " · refreshing" : ""}</span>}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <label className="text-xs text-muted">
              Range
              <select className="ml-2 rounded-md border border-line bg-elevated px-2 py-1 text-sm text-paper" value={window} onChange={(event) => setQuery({ range: event.target.value as WindowCode })}>
                {WINDOWS.map((item) => <option key={item} value={item}>{WINDOW_LABEL[item]}</option>)}
              </select>
            </label>
            <Button variant="secondary" loading={refreshing} loadingLabel="Refreshing…" onClick={() => void load(true)}>Refresh</Button>
            {overview?.capabilities.canCreateRun && <ButtonLink href="/runs/new">New support run</ButtonLink>}
          </div>
        }
      />
      {error && !overview && <ErrorState title="Unable to load operations data" onRetry={() => void load(false)}>{error}</ErrorState>}
      {error && overview && <ErrorState title="Refresh failed" onRetry={() => void load(true)}>{error}</ErrorState>}
      {loading && !overview && (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" aria-busy="true" aria-label="Loading operations">
          {Array.from({ length: 8 }, (_, index) => <Skeleton key={index} className="h-[7.25rem]" />)}
        </div>
      )}
      {summary && empty && (
        <EmptyState title="No agent activity yet" actionHref={overview.capabilities.canCreateRun ? "/runs/new" : undefined} actionLabel="New support run">
          Start a support run to begin collecting operational data for this workspace and window.
        </EmptyState>
      )}
      {summary && !empty && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <MetricCard label="Runs" value={summary.runs.value} context={`${summary.inProgress.value} still open · ${windowLabel(summary)}`} hint={summary.runs.definition} />
            <MetricCard label="Completion" value={rateText(summary.completion)} context={summary.completion.status === "OK" ? `${summary.completed.value} of ${summary.terminalRuns.value} terminal runs` : "No finished runs yet"} hint={summary.completion.definition} />
            <MetricCard label="Failure" value={rateText(summary.failure)} context={summary.failure.status === "OK" ? `${summary.failure.numerator} unsuccessful · ${summary.cancelled.value} cancelled, not counted as failure` : "No finished runs yet"} hint={summary.failure.definition} />
            <MetricCard label="Pending approvals" value={summary.pendingApprovals.value} hint={summary.pendingApprovals.definition} />
          </div>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <MetricCard label="p95" value={durationValue(summary.latency.status, summary.latency.p95Ms)} context="End-to-end run duration" hint={latencyHint(summary, summary.latency.p95Ms)} tone={summary.latency.high ? "warning" : "default"} note={summary.latency.high ? "High latency observed" : undefined} />
            <MetricCard label="Approval wait" value={durationValue(summary.approvalWait.approved.status, summary.approvalWait.approved.valueMs)} context={approvalContext(summary)} hint={summary.approvalWait.definition} />
            <MetricCard label="Queue depth" value={summary.queueDepth.value} context="Open jobs in this workspace" hint={summary.queueDepth.definition} />
            <MetricCard label="Estimated cost" value={costText(summary)} context={costContext(summary)} hint={summary.cost.definition} />
          </div>
          <details className="rounded-md border border-line bg-elevated px-4 py-3">
            <summary className="cursor-pointer text-sm text-paper">More metrics</summary>
            <div className="mt-3 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
              <MetricCard label="p50" value={durationValue(summary.latency.status, summary.latency.p50Ms)} context="End-to-end run duration" hint={latencyHint(summary, summary.latency.p50Ms)} />
              <MetricCard label="p99" value={durationValue(summary.latency.status, summary.latency.p99Ms)} context="End-to-end run duration" hint={latencyHint(summary, summary.latency.p99Ms)} />
              <MetricCard label="Policy denial rate" value={rateText(summary.policyDenial)} context={summary.policyDenial.status === "OK" ? `${summary.policyDenial.denials} of ${summary.policyDenial.decisions} decisions` : "No policy decisions yet"} hint={summary.policyDenial.definition} />
              <MetricCard label="Tokens" value={summary.tokens.value} context="Recorded input + output" hint={summary.tokens.definition} />
            </div>
          </details>
          <div className="grid gap-3 lg:grid-cols-2">
            <Card>
              <SectionHeader title="Active runs" description="Non-terminal runs you are allowed to see." />
              {overview.activeRuns.length === 0 ? (
                <p className="mt-3 text-sm text-muted">No active runs in this window.</p>
              ) : (
                <ul className="mt-3 space-y-2">
                  {overview.activeRuns.map((run) => (
                    <li key={run.id}>
                      <Link href={run.href} className="block rounded-md border border-line px-3 py-2 hover:bg-white/[0.03] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <Mono>{shortId(run.id)}</Mono>
                          <StatusBadge status={run.state} />
                        </div>
                        <p className="mt-1 text-sm text-paper">{run.agentName}</p>
                        <p className="text-xs text-muted">Started <Timestamp value={run.startedAt || run.createdAt} /></p>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
            <Card>
              <SectionHeader title="Attention" description="Deterministic signals from this window." />
              {overview.attention.length === 0 ? (
                <p className="mt-3 text-sm text-muted">Nothing requires attention in this window.</p>
              ) : (
                <ul className="mt-3 space-y-2">
                  {overview.attention.map((item) => (
                    <li key={item.code + (item.runId || "")} className="rounded-md border border-line px-3 py-2">
                      <div className="flex items-center justify-between gap-2">
                        <p className="text-sm text-paper">{item.title}</p>
                        <span className="text-[11px] uppercase tracking-[0.12em] text-muted">{item.severity}</span>
                      </div>
                      <p className="mt-1 text-xs text-muted">{item.reason}</p>
                      {item.href && <Link className="mt-2 inline-block text-xs text-info hover:underline" href={item.href}>{item.runId ? "Inspect run" : "Open"}</Link>}
                    </li>
                  ))}
                </ul>
              )}
            </Card>
          </div>
          <Card>
            <SectionHeader title="Run activity" description="Counts of runs created in each bucket. Cancelled is not unsuccessful." />
            <div className="mt-3">
              <SparkBars
                points={overview.activityTrend.map((point) => ({ key: point.bucket, value: point.runs }))}
                label={`${overview.activityTrend.reduce((sum, point) => sum + point.runs, 0)} runs across ${overview.activityTrend.length} buckets.`}
                empty="Not enough timestamped runs for an activity trend."
              />
            </div>
            {overview.latencyTrend.length === 0 ? (
              <p className="mt-3 text-sm text-muted">Not enough completed days for a percentile trend.</p>
            ) : (
              <p className="mt-3 text-sm text-muted">
                Latency trend uses {overview.latencyTrend.length} buckets. Latest p95{" "}
                {overview.latencyTrend.at(-1)?.p95Ms == null ? "has no sample" : formatDuration(overview.latencyTrend.at(-1)!.p95Ms!)}.
              </p>
            )}
          </Card>
          <div className="grid gap-3 lg:grid-cols-2">
            <Card>
              <SectionHeader title="Recent failures" description="FAILED and TIMED_OUT only. Cancelled is excluded." />
              {overview.recentFailures.length === 0 ? (
                <p className="mt-3 text-sm text-muted">No failed or timed-out runs in this window.</p>
              ) : (
                <ul className="mt-3 space-y-2">
                  {overview.recentFailures.map((run) => (
                    <li key={run.id}>
                      <Link href={run.href} className="block rounded-md border border-line px-3 py-2 hover:bg-white/[0.03]">
                        <div className="flex items-center justify-between gap-2">
                          <Mono>{shortId(run.id)}</Mono>
                          <StatusBadge status={run.state} />
                        </div>
                        <p className="mt-1 text-xs text-muted">{run.failureCategory || "No category"} · {run.agentName} · <DurationText ms={run.durationMs} /></p>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
            <Card>
              <SectionHeader title="Approvals" description={overview.capabilities.canReadApprovals ? "Latest decisions in this window." : "Counts only. Reviewers and Admins can open the queue."} />
              {!overview.capabilities.canReadApprovals && (
                <p className="mt-3 text-sm text-muted">{summary.pendingApprovals.value} pending. Approval payloads are withheld for this role.</p>
              )}
              {overview.capabilities.canReadApprovals && overview.approvals.length === 0 && (
                <p className="mt-3 text-sm text-muted">No approvals requiring attention.</p>
              )}
              {overview.capabilities.canReadApprovals && overview.approvals.length > 0 && (
                <ul className="mt-3 space-y-2">
                  {overview.approvals.map((row) => (
                    <li key={row.id} className="rounded-md border border-line px-3 py-2">
                      <div className="flex items-center justify-between gap-2">
                        <StatusBadge status={row.status} />
                        <Timestamp value={row.requestedAt} />
                      </div>
                      <p className="mt-1 text-sm text-paper">{row.tool}</p>
                      <Link className="mt-1 inline-block text-xs text-info hover:underline" href={`/approvals/${row.id}`}>Review action</Link>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
          </div>
          <section>
            <SectionHeader
              title="Recent runs"
              description="Newest first, 20 per page, created_at then id."
              action={
                <label className="text-xs text-muted">
                  Status
                  <select className="ml-2 rounded-md border border-line bg-elevated px-2 py-1 text-sm text-paper" value={status} onChange={(event) => setQuery({ status: event.target.value as RunFilter, page: 0 })}>
                    {RUN_FILTERS.map((item) => <option key={item} value={item}>{item.replace("_", " ")}</option>)}
                  </select>
                </label>
              }
            />
            <div className="mt-3">
              <DataTable
                columns={runColumns()}
                rows={overview.recentRuns.items}
                getKey={(row) => row.id}
                empty={<EmptyState title="No runs for this filter">Change the status filter or the time range.</EmptyState>}
                pagination={
                  <div className="flex items-center justify-between gap-2">
                    <span>{overview.recentRuns.total} matching</span>
                    <span className="flex gap-2">
                      <Button variant="ghost" disabled={page === 0} onClick={() => setQuery({ page: page - 1 })}>Previous</Button>
                      <Button variant="ghost" disabled={(page + 1) * overview.recentRuns.size >= overview.recentRuns.total} onClick={() => setQuery({ page: page + 1 })}>Next</Button>
                    </span>
                  </div>
                }
              />
            </div>
          </section>
          <Card>
            <SectionHeader title="Agent activity" description="ACTIVE / INACTIVE is the stored agent status, not a health score." />
            {overview.agents.length === 0 ? (
              <p className="mt-3 text-sm text-muted">No agents in this workspace.</p>
            ) : (
              <ul className="mt-3 grid gap-2 md:grid-cols-2">
                {overview.agents.map((agent) => (
                  <li key={agent.id} className="rounded-md border border-line px-3 py-2">
                    <div className="flex items-center justify-between gap-2">
                      <p className="text-sm text-paper">{agent.name}</p>
                      <StatusBadge status={agent.status} />
                    </div>
                    <p className="mt-1 text-xs text-muted">
                      {agent.runs} runs · {agent.completion == null ? "No terminal runs" : `${Math.round(agent.completion * 100)}% completion`}
                      {agent.unsuccessful > 0 ? ` · ${agent.unsuccessful} unsuccessful` : ""}
                    </p>
                    {agent.lastRunAt && <p className="text-xs text-muted">Last run <Timestamp value={agent.lastRunAt} /></p>}
                    {<Link className="mt-1 inline-block text-xs text-info hover:underline" href="/agents">View agents</Link>}
                  </li>
                ))}
              </ul>
            )}
          </Card>
          {overview.activity.length > 0 && (
            <Card>
              <SectionHeader title="Recent activity" description="Newest run events in this window, bounded to 20." />
              <ul className="mt-3 space-y-2">
                {overview.activity.map((event, index) => (
                  <li key={event.runId + event.createdAt + index} className="flex flex-wrap items-center gap-2 text-sm">
                    <Timestamp value={event.createdAt} />
                    <StatusBadge status={event.state} />
                    <span className="text-muted">{event.eventType}</span>
                    <Link className="text-info hover:underline" href={`/runs/${event.runId}`}>{shortId(event.runId)}</Link>
                    <span className="text-muted">{event.agentName}</span>
                  </li>
                ))}
              </ul>
            </Card>
          )}
        </>
      )}
    </Page>
  );
}

function runColumns() {
  return [
    { key: "state", header: "Status", cell: (row: OperationsRun) => <StatusBadge status={row.state} /> },
    { key: "run", header: "Run", cell: (row: OperationsRun) => <Link className="text-info hover:underline" href={row.href}><Mono>{shortId(row.id)}</Mono></Link> },
    { key: "agent", header: "Agent", cell: (row: OperationsRun) => row.agentName },
    { key: "started", header: "Started", cell: (row: OperationsRun) => <Timestamp value={row.startedAt || row.createdAt} /> },
    { key: "duration", header: "Duration", cell: (row: OperationsRun) => <DurationText ms={row.durationMs} /> },
    { key: "outcome", header: "Outcome", cell: (row: OperationsRun) => row.failureCategory || row.state },
    { key: "cost", header: "Cost", cell: (row: OperationsRun) => row.estimatedCostUsd == null ? "—" : `$${Number(row.estimatedCostUsd).toFixed(2)}` },
  ];
}

function parseWindow(value: string | null): WindowCode {
  return WINDOWS.includes((value || "ALL").toUpperCase() as WindowCode) ? (value || "ALL").toUpperCase() as WindowCode : "ALL";
}

function parseFilter(value: string | null): RunFilter {
  return RUN_FILTERS.includes((value || "ALL").toUpperCase() as RunFilter) ? (value || "ALL").toUpperCase() as RunFilter : "ALL";
}

function windowLabel(summary: OperationsSummary) {
  return summary.window === "ALL_TIME" ? "all time" : summary.window;
}

function shortId(id: string) {
  return id.slice(0, 8);
}

function relative(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const seconds = Math.round((Date.now() - date.getTime()) / 1000);
  if (seconds < 5) return "just now";
  if (seconds < 60) return `${seconds}s ago`;
  return `${Math.round(seconds / 60)}m ago`;
}

function rateText(rate: { value: number | null; status: string }) {
  if (rate.status !== "OK" || rate.value == null) return "No data";
  return `${Math.round(rate.value * 100)}%`;
}

function durationValue(status: string, ms: number | null) {
  if (status !== "OK" || ms == null) return "No data";
  return <DurationText ms={ms} />;
}

function latencyHint(summary: OperationsSummary, ms: number | null) {
  const exact = ms == null ? "No sample." : `Exact value: ${formatExactMs(ms)}.`;
  const invalid = summary.latency.invalidDurationCount > 0
    ? ` ${summary.latency.invalidDurationCount} invalid duration${summary.latency.invalidDurationCount === 1 ? "" : "s"} excluded.`
    : "";
  return `${summary.latency.definition} ${exact}${invalid}`;
}

function approvalContext(summary: OperationsSummary) {
  const expired = summary.approvalWait.expired;
  if (expired.status === "OK" && expired.valueMs != null) {
    return `Approved only · expired average ${formatDuration(expired.valueMs)}, not included`;
  }
  return "Approved decisions only";
}

function costText(summary: OperationsSummary) {
  if (summary.cost.pricingStatus === "NO_USAGE" || summary.cost.pricingStatus === "NO_TOKENS" || summary.cost.pricingStatus === "PRICING_UNAVAILABLE") {
    return summary.cost.pricingStatus === "PRICING_UNAVAILABLE" ? "Pricing unavailable" : "No data";
  }
  return new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(summary.cost.amount));
}

function costContext(summary: OperationsSummary) {
  if (summary.cost.pricingStatus === "CONFIGURED_ZERO") return "Configured zero-price model";
  if (summary.cost.pricingStatus === "NO_TOKENS") return "No tokens were recorded";
  if (summary.cost.pricingStatus === "NO_USAGE") return "No runs in this workspace";
  if (summary.cost.pricingStatus === "PRICING_UNAVAILABLE") return "No configured price for a model in use";
  return "Configured list price";
}
