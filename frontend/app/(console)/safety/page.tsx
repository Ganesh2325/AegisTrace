"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api } from "../../../lib/api";
import { Button } from "../../../components/ui/Button";
import { Card } from "../../../components/ui/Card";
import { SelectField } from "../../../components/ui/Field";
import { MetricCard } from "../../../components/ui/MetricCard";
import { Page, PageHeader, SectionHeader } from "../../../components/ui/PageHeader";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/States";
import { StatusBadge } from "../../../components/ui/StatusBadge";
import { Mono, Timestamp } from "../../../components/ui/Type";

type Signal = { id: string; type: string; disposition: string; severity: string; runId?: string | null; evaluationResultId?: string | null; evidence: Record<string, unknown>; createdAt: string };
type SignalPage = { items: Signal[]; total: number; page: number; size: number };
type Overview = { generatedAt: string; counts: { disposition: string; count: number }[]; recent: Signal[]; definition: string };
const dispositions = ["", "DETECTED", "BLOCKED", "APPROVED", "REJECTED", "ABSTAINED", "FAILED", "UNKNOWN"];

export default function SafetyPage() {
  const [overview, setOverview] = useState<Overview | null>(null);
  const [signals, setSignals] = useState<SignalPage | null>(null);
  const [disposition, setDisposition] = useState("");
  const [page, setPage] = useState(0);
  const [error, setError] = useState("");
  const load = useCallback(async () => {
    try {
      const [summary, rows] = await Promise.all([
        api<Overview>("/api/v1/safety/overview"),
        api<SignalPage>(`/api/v1/safety/signals?page=${page}${disposition ? `&disposition=${disposition}` : ""}`),
      ]);
      setOverview(summary);
      setSignals(rows);
      setError("");
    } catch (err) { setError(err instanceof Error ? err.message : "Unable to load safety center"); }
  }, [disposition, page]);
  useEffect(() => { void load(); }, [load]);
  const count = (key: string) => overview?.counts.find((item) => item.disposition === key)?.count || 0;
  return (
    <Page width="wide">
      <PageHeader eyebrow="Deterministic controls" title="Safety Center" description="Real policy, approval, abstention, prompt-injection, and evaluation signals. Disposition describes what happened; it is not an AI confidence score." />
      {error && <ErrorState title="Unable to load Safety Center" onRetry={() => void load()}>{error}</ErrorState>}
      {!overview && !error && <Skeleton className="h-32" />}
      {overview && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <MetricCard label="Detected" value={count("DETECTED")} hint="Signals detected before a final control outcome." />
            <MetricCard label="Blocked" value={count("BLOCKED")} hint="Deterministic controls prevented the action." />
            <MetricCard label="Abstained" value={count("ABSTAINED")} hint="Runs that explicitly declined to answer." />
            <MetricCard label="Failed" value={count("FAILED")} hint="Safety evaluation checks that failed." />
          </div>
          <Card>
            <SectionHeader title="Disposition semantics" description={overview.definition} />
            <div className="mt-3 flex flex-wrap gap-2">{dispositions.slice(1).map((item) => <StatusBadge key={item} status={item} />)}</div>
          </Card>
        </>
      )}
      <Card>
        <SectionHeader title="Safety signals" description="Bounded evidence references only; document contents and private reasoning are not included." action={<div className="w-44"><SelectField label="Disposition" value={disposition} onChange={(e) => { setDisposition(e.target.value); setPage(0); }}>{dispositions.map((item) => <option key={item} value={item}>{item || "All"}</option>)}</SelectField></div>} />
        {signals?.items.length === 0 && <EmptyState title="No safety signals">No real signal matches this filter.</EmptyState>}
        <ul className="mt-3 divide-y divide-line">
          {signals?.items.map((signal) => (
            <li key={signal.id} className="py-3">
              <div className="flex flex-wrap items-center gap-2"><StatusBadge status={signal.disposition} /><Mono>{signal.type}</Mono><StatusBadge status={signal.severity === "HIGH" ? "FAILED" : signal.severity === "MEDIUM" ? "PENDING" : "NO_DATA"} label={signal.severity.toLowerCase()} /><span className="ml-auto"><Timestamp value={signal.createdAt} /></span></div>
              <div className="mt-2 flex flex-wrap gap-3 text-xs text-muted">
                {signal.runId && <Link className="text-info hover:underline" href={`/runs/${signal.runId}`}>Run {signal.runId.slice(0, 8)}</Link>}
                <span>{Object.entries(signal.evidence).map(([key, value]) => `${key}: ${String(value)}`).join(" · ")}</span>
              </div>
            </li>
          ))}
        </ul>
        {signals && signals.total > signals.size && <div className="mt-3 flex justify-end gap-2"><Button variant="ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button><Button variant="ghost" disabled={(page + 1) * signals.size >= signals.total} onClick={() => setPage(page + 1)}>Next</Button></div>}
      </Card>
    </Page>
  );
}
