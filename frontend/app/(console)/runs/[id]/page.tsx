"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { api, apiBase } from "../../../../lib/api";
import { Button } from "../../../../components/ui/Button";
import { Card } from "../../../../components/ui/Card";
import { Page, SectionHeader } from "../../../../components/ui/PageHeader";
import { StatusBadge } from "../../../../components/ui/StatusBadge";
import { ErrorState, Skeleton } from "../../../../components/ui/States";
import { Mono, Timestamp } from "../../../../components/ui/Type";

type Citation = { documentTitle?: string; section?: string; quote?: string; score?: number; chunkId?: string };
type Run = {
  id: string; state: string; question: string; draftAnswer?: string; finalResponse?: string;
  citations: Citation[]; traceId: string; failureCategory?: string; errorMessage?: string;
  inputTokens: number; outputTokens: number; estimatedCostUsd: number; provider: string; model: string;
  agentVersionId: string;
};
type EventRow = { sequence: number; eventType: string; state: string; payload: Record<string, unknown>; createdAt: string };

export default function RunDetail() {
  const params = useParams<{ id: string }>();
  const [run, setRun] = useState<Run | null>(null);
  const [events, setEvents] = useState<EventRow[]>([]);
  const [error, setError] = useState("");
  const [cancelling, setCancelling] = useState(false);

  useEffect(() => {
    let stop = false;
    const load = () => api<Run>(`/api/v1/runs/${params.id}`).then((row) => { if (!stop) setRun(row); }).catch((err) => setError(err.message));
    load();
    const source = new EventSource(`${apiBase}/api/v1/runs/${params.id}/events`, { withCredentials: true });
    source.onmessage = () => load();
    source.addEventListener("RUN_STARTED", () => load());
    ["RETRIEVAL_COMPLETED", "MODEL_COMPLETED", "TOOL_PROPOSED", "POLICY_DECIDED", "APPROVAL_REQUIRED", "APPROVAL_APPROVED", "APPROVAL_REJECTED", "TOOL_COMPLETED", "RUN_COMPLETED", "RUN_FAILED", "RUN_CANCELLED", "RUN_TIMED_OUT"].forEach((name) => {
      source.addEventListener(name, () => load());
    });
    const poll = window.setInterval(load, 2500);
    api<EventRow[]>(`/api/v1/runs/${params.id}/timeline`).then(setEvents).catch(() => undefined);
    const timelinePoll = window.setInterval(() => {
      api<EventRow[]>(`/api/v1/runs/${params.id}/timeline`).then(setEvents).catch(() => undefined);
    }, 2500);
    return () => { stop = true; source.close(); window.clearInterval(poll); window.clearInterval(timelinePoll); };
  }, [params.id]);

  if (error) return <ErrorState title="Unable to load this run">{error}</ErrorState>;
  if (!run) return <Skeleton className="h-40" />;
  const answer = run.finalResponse || run.draftAnswer;

  return (
    <Page width="full">
    <div className="grid gap-4 xl:grid-cols-[1.2fr_0.8fr]">
      <section>
        <div className="flex flex-wrap items-center gap-2">
          <StatusBadge status={run.state} />
          <Mono>{run.provider} / {run.model}</Mono>
        </div>
        <h1 className="mt-2 text-xl font-semibold tracking-tight">{run.question}</h1>
        <p className="mt-2"><Mono>trace {run.traceId}</Mono></p>
        {run.failureCategory && <p className="mt-3 text-sm text-danger">{run.failureCategory}: {run.errorMessage}</p>}
        <Card className="mt-4 whitespace-pre-wrap text-sm leading-6">{answer || "Working…"}</Card>
        <div className="mt-4">
          <SectionHeader title="Citations" />
          <ul className="mt-2 space-y-2">
            {(run.citations || []).map((citation, index) => (
              <li key={citation.chunkId || index}>
                <Card variant="compact">
                  <div className="text-sm text-warning">{citation.documentTitle} {citation.section ? `· ${citation.section}` : ""}</div>
                  <p className="mt-1 text-sm text-muted">{citation.quote}</p>
                </Card>
              </li>
            ))}
            {(!run.citations || run.citations.length === 0) && <li className="text-sm text-muted">No citations yet.</li>}
          </ul>
        </div>
        <p className="mt-4"><Mono>Tokens {run.inputTokens + run.outputTokens} · estimated cost ${Number(run.estimatedCostUsd || 0).toFixed(6)} · version {run.agentVersionId}</Mono></p>
        {run.state !== "COMPLETED" && run.state !== "FAILED" && run.state !== "CANCELLED" && run.state !== "TIMED_OUT" && (
          <Button className="mt-3" variant="ghost" loading={cancelling} loadingLabel="Cancelling…" onClick={async () => {
            if (cancelling) return;
            setCancelling(true);
            try { await api(`/api/v1/runs/${run.id}/cancel`, { method: "POST" }); }
            finally { setCancelling(false); }
          }}>Cancel run</Button>
        )}
      </section>
      <section>
        <SectionHeader title="Timeline" />
        <ol className="mt-3 space-y-2">
          {events.map((event) => (
            <li key={event.sequence}>
              <Card variant="compact">
                <div className="flex justify-between gap-3 text-sm">
                  <span>{label(event.eventType)}</span>
                  <StatusBadge status={event.state} />
                </div>
                <div className="mt-1 text-xs text-muted">{summary(event) || <Timestamp value={event.createdAt} />}</div>
              </Card>
            </li>
          ))}
        </ol>
      </section>
    </div>
    </Page>
  );
}

function label(type: string) {
  return type.replaceAll("_", " ").toLowerCase();
}

function summary(event: EventRow) {
  const payload = event.payload || {};
  if (payload.chunkCount !== undefined) return `${payload.chunkCount} chunks`;
  if (payload.tool) return String(payload.tool);
  if (payload.decision) return `${payload.decision} ${payload.code || ""}`;
  if (payload.ticketId) return `ticket ${payload.ticketId}`;
  if (payload.category) return String(payload.category);
  return "";
}
