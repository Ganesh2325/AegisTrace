"use client";

import Link from "next/link";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { api } from "../../../lib/api";
import { formatDuration, formatExactMs } from "../../../lib/duration";
import { OBS_WINDOWS, compactId, parseObsWindow, type ObservabilityOverview, type ObsWindow } from "../../../lib/observability";
import { Button, ButtonLink } from "../../../components/ui/Button";
import { Card } from "../../../components/ui/Card";
import { MetricCard } from "../../../components/ui/MetricCard";
import { Page, PageHeader, SectionHeader } from "../../../components/ui/PageHeader";
import { EmptyState, ErrorState, Skeleton, UnavailableState } from "../../../components/ui/States";
import { StatusBadge } from "../../../components/ui/StatusBadge";
import { DurationText, Mono, TextLink, Timestamp } from "../../../components/ui/Type";
import { ObservabilityNav } from "./ObservabilityNav";

const WINDOW_LABEL: Record<ObsWindow, string> = {
  "1H": "Previous 1 hour",
  "24H": "Previous 24 hours",
  "7D": "Previous 168 hours",
  "30D": "Previous 720 hours",
};

export default function ObservabilityPage() {
  return (
    <Suspense fallback={<Page width="wide"><Skeleton className="h-32" /></Page>}>
      <Overview />
    </Suspense>
  );
}

function Overview() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const window = parseObsWindow(params.get("range"));
  const [data, setData] = useState<ObservabilityOverview | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const inflight = useRef(false);

  const load = useCallback(async (background: boolean) => {
    if (inflight.current) return;
    inflight.current = true;
    if (background) setRefreshing(true);
    else setLoading(true);
    try {
      const row = await api<ObservabilityOverview>(`/api/v1/observability/overview?window=${window}`);
      setData(row);
      setError("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load observability");
      if (!background) setData(null);
    } finally {
      inflight.current = false;
      setLoading(false);
      setRefreshing(false);
    }
  }, [window]);

  useEffect(() => { void load(false); }, [load]);

  function setRange(next: ObsWindow) {
    const search = new URLSearchParams(params.toString());
    search.set("range", next);
    router.replace(`${pathname}?${search.toString()}`);
  }

  const metrics = data?.metrics;
  const tracesDown = data?.telemetry.traces === "UNAVAILABLE" || data?.telemetry.traces === "NOT_CONFIGURED";
  const prometheusDown = data?.telemetry.prometheus === "UNAVAILABLE";

  return (
    <Page width="wide">
      <PageHeader
        eyebrow="Execution"
        title="Observability"
        description="How the system behaved. Product metrics come from Postgres. Spans come from Jaeger when the collector stored them. Audit is a separate history."
        status={data && <span className="text-xs text-muted">Updated {relative(data.generatedAt)}{refreshing ? " · refreshing" : ""}</span>}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <label className="text-xs text-muted">
              Range
              <select className="ml-2 rounded-md border border-line bg-elevated px-2 py-1 text-sm text-paper" value={window} onChange={(event) => setRange(event.target.value as ObsWindow)}>
                {OBS_WINDOWS.map((item) => <option key={item} value={item}>{WINDOW_LABEL[item]}</option>)}
              </select>
            </label>
            <Button variant="secondary" loading={refreshing} loadingLabel="Refreshing…" onClick={() => void load(true)}>Refresh</Button>
            {data?.telemetry.jaegerUrl && <TextLink href={data.telemetry.jaegerUrl} external>Jaeger</TextLink>}
            {data?.telemetry.grafanaUrl && <TextLink href={data.telemetry.grafanaUrl} external>Grafana</TextLink>}
          </div>
        }
      />
      <ObservabilityNav />
      {error && !data && <ErrorState title="Unable to load observability" onRetry={() => void load(false)}>{error}</ErrorState>}
      {error && data && <ErrorState title="Refresh failed" onRetry={() => void load(true)}>{error}</ErrorState>}
      {loading && !data && <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-[7.25rem]" />)}</div>}
      {tracesDown && (
        <UnavailableState title="Trace backend unavailable">
          {data?.telemetry.traces === "NOT_CONFIGURED"
            ? "Jaeger is not configured. Run metadata and product events are still shown."
            : "Jaeger did not respond. Run metadata and product events are still shown. Missing traces are not zeros."}
        </UnavailableState>
      )}
      {prometheusDown && (
        <UnavailableState title="Metrics unavailable">Prometheus did not respond. Workspace counters below still come from Postgres, not Prometheus scrape totals.</UnavailableState>
      )}
      {metrics && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            <MetricCard label="Runs" value={metrics.runs.value} context={WINDOW_LABEL[window]} hint={metrics.runs.definition} />
            <MetricCard label="Error rate" value={rateText(metrics.failure)} context={metrics.failure.status === "OK" ? `${metrics.failure.numerator} unsuccessful terminal runs` : "No finished runs yet"} hint={metrics.failure.definition} />
            <MetricCard label="P95" value={durationValue(metrics.latency.status, metrics.latency.p95Ms)} context="End-to-end including approval wait" hint={metrics.latency.definition} />
            <MetricCard label="P99" value={durationValue(metrics.latency.status, metrics.latency.p99Ms)} context={`${metrics.latency.sampleCount} samples`} hint={`Exact: ${metrics.latency.p99Ms == null ? "none" : formatExactMs(metrics.latency.p99Ms)}`} />
            <MetricCard label="Active runs" value={metrics.inProgress.value} hint={metrics.inProgress.definition} />
            <MetricCard label="Tool executions" value={data.toolExecutions.value} hint={data.toolExecutions.definition} />
            <MetricCard label="Policy denials" value={rateText(metrics.policyDenial)} context={metrics.policyDenial.status === "OK" ? `${metrics.policyDenial.denials} of ${metrics.policyDenial.decisions} decisions` : "No policy decisions yet"} hint={metrics.policyDenial.definition} />
          </div>
          <div className="grid gap-3 lg:grid-cols-2">
            <Card>
              <SectionHeader title="Latency" description="Canonical Phase 1 percentiles. Agent-only time is not stored separately." />
              {metrics.latency.status !== "OK" ? (
                <p className="mt-3 text-sm text-muted">Not enough data.</p>
              ) : (
                <dl className="mt-3 grid gap-2 text-sm">
                  <div className="flex justify-between"><dt>P50</dt><dd><DurationText ms={metrics.latency.p50Ms || 0} /></dd></div>
                  <div className="flex justify-between"><dt>P95</dt><dd><DurationText ms={metrics.latency.p95Ms || 0} /></dd></div>
                  <div className="flex justify-between"><dt>P99</dt><dd><DurationText ms={metrics.latency.p99Ms || 0} /></dd></div>
                  <div className="flex justify-between"><dt>Approval wait (approved mean)</dt><dd>{metrics.approvalWait.approved.status === "OK" ? <DurationText ms={metrics.approvalWait.approved.valueMs || 0} /> : "No data"}</dd></div>
                </dl>
              )}
            </Card>
            <Card>
              <SectionHeader title="Services" description="Instrumentation facts, not health claims." />
              <ul className="mt-3 space-y-2 text-sm">
                {data.services.map((service) => (
                  <li key={service.name}>
                    <p className="text-paper">{service.name}</p>
                    <p className="text-xs text-muted">{service.telemetry}</p>
                  </li>
                ))}
              </ul>
            </Card>
          </div>
          <Card>
            <SectionHeader title="Recent errors" description="Failed and timed-out runs in this window. Grouped by failure category on the errors page." action={<ButtonLink href="/observability/errors" variant="ghost">All errors</ButtonLink>} />
            {data.recentErrors.length === 0 ? (
              <p className="mt-3 text-sm text-muted">No failed runs in this window.</p>
            ) : (
              <ul className="mt-3 space-y-2">
                {data.recentErrors.map((item) => (
                  <li key={item.runId} className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line px-3 py-2 text-sm">
                    <div>
                      <StatusBadge status="ERROR" />
                      <span className="ml-2">{item.category || item.code || item.state}</span>
                      <p className="mt-1 text-xs text-muted">{item.agent} · <Timestamp value={item.at} /></p>
                    </div>
                    <div className="flex gap-2">
                      <ButtonLink href={`/runs/${item.runId}`} variant="ghost">Run</ButtonLink>
                      <ButtonLink href={`/observability/traces/${encodeURIComponent(item.traceId)}`} variant="ghost">Trace</ButtonLink>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </Card>
          <Card>
            <SectionHeader title="Recent traces" description="One row per run that stored a trace id. Spans load on the detail page." action={<ButtonLink href="/observability/traces" variant="ghost">Trace explorer</ButtonLink>} />
            {data.recentTraces.length === 0 ? (
              <EmptyState title="No traces available">Run an agent execution to generate trace data.</EmptyState>
            ) : (
              <ul className="mt-3 space-y-2">
                {data.recentTraces.map((item) => (
                  <li key={item.traceId + item.runId}>
                    <Link href={`/observability/traces/${encodeURIComponent(item.traceId)}`} className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line px-3 py-2 hover:bg-white/[0.03] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent">
                      <Mono>{compactId(item.traceId, 10)}</Mono>
                      <StatusBadge status={item.status} />
                      <span className="text-sm text-muted">{item.durationMs == null ? "—" : formatDuration(item.durationMs)}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </>
      )}
    </Page>
  );
}

function rateText(rate: { value: number | null; status: string }) {
  if (rate.status !== "OK" || rate.value == null) return "No data";
  return `${Math.round(rate.value * 1000) / 10}%`;
}

function durationValue(status: string, ms: number | null) {
  if (status !== "OK" || ms == null) return "No data";
  return <DurationText ms={ms} />;
}

function relative(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const seconds = Math.round((Date.now() - date.getTime()) / 1000);
  if (seconds < 5) return "just now";
  if (seconds < 60) return `${seconds}s ago`;
  return `${Math.round(seconds / 60)}m ago`;
}
