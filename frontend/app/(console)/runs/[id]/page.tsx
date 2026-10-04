"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { api, apiBase } from "../../../../lib/api";

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

  if (error) return <p className="text-rose">{error}</p>;
  if (!run) return <div className="h-40 animate-pulse rounded-md bg-panel" />;
  const answer = run.finalResponse || run.draftAnswer;

  return (
    <div className="grid gap-4 xl:grid-cols-[1.2fr_0.8fr]">
      <section>
        <p className="kicker">Run {run.state}</p>
        <h1 className="mt-1 text-xl font-semibold">{run.question}</h1>
        <p className="mt-2 font-mono text-xs text-mist">trace {run.traceId} · {run.provider} / {run.model}</p>
        {run.failureCategory && <p className="mt-3 text-sm text-rose">{run.failureCategory}: {run.errorMessage}</p>}
        <article className="panel mt-4 whitespace-pre-wrap p-4 text-sm leading-6">{answer || "Working…"}</article>
        <div className="mt-4">
          <h2 className="kicker">Citations</h2>
          <ul className="mt-2 space-y-2">
            {(run.citations || []).map((citation, index) => (
              <li key={citation.chunkId || index} className="panel p-3 text-sm">
                <div className="text-amber">{citation.documentTitle} {citation.section ? `· ${citation.section}` : ""}</div>
                <p className="mt-1 text-mist">{citation.quote}</p>
              </li>
            ))}
            {(!run.citations || run.citations.length === 0) && <li className="text-sm text-mist">No citations yet.</li>}
          </ul>
        </div>
        <p className="mt-4 text-xs text-mist">Tokens {run.inputTokens + run.outputTokens} · estimated cost ${Number(run.estimatedCostUsd || 0).toFixed(6)} · version {run.agentVersionId}</p>
        {run.state !== "COMPLETED" && run.state !== "FAILED" && run.state !== "CANCELLED" && run.state !== "TIMED_OUT" && (
          <button className="btn-ghost mt-3" onClick={() => api(`/api/v1/runs/${run.id}/cancel`, { method: "POST" })}>Cancel run</button>
        )}
      </section>
      <section>
        <h2 className="kicker">Timeline</h2>
        <ol className="mt-3 space-y-2">
          {events.map((event) => (
            <li key={event.sequence} className="panel px-3 py-2 text-sm">
              <div className="flex justify-between gap-3">
                <span>{label(event.eventType)}</span>
                <span className="font-mono text-xs text-mist">{event.state}</span>
              </div>
              <div className="mt-1 text-xs text-mist">{summary(event)}</div>
            </li>
          ))}
        </ol>
      </section>
    </div>
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
  return event.createdAt;
}
