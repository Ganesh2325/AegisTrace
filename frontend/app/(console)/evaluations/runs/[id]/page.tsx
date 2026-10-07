"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../../../../../lib/api";
import { isExecutionActive } from "../../../../../lib/evaluations";
import { Button, ButtonLink } from "../../../../../components/ui/Button";
import { Card } from "../../../../../components/ui/Card";
import { Page, PageHeader, SectionHeader } from "../../../../../components/ui/PageHeader";
import { ErrorState, Skeleton } from "../../../../../components/ui/States";
import { StatusBadge } from "../../../../../components/ui/StatusBadge";
import { CodeBlock, Mono, Timestamp } from "../../../../../components/ui/Type";
import { EvaluationNav } from "../../EvaluationNav";

type Check = { key: string; status: string; score: number | null; failureCategory?: string | null; explanation: string; evidence: Record<string, unknown> };
type Result = { id: string; status: string; score: number | null; failureCategory?: string | null; explanation?: string | null; runId?: string | null; caseKey: string; caseVersion: number; caseName: string; category: string; checks: Check[] };
type Detail = {
  id: string; status: string; suiteName: string; suiteKey: string; suiteVersion: number;
  agentName: string; agentVersion: number; agentVersionId: string;
  knowledgeName: string; knowledgeVersion: number; knowledgeVersionId: string;
  provider: string; model: string; evaluatorVersion: string; traceId?: string | null;
  createdAt: string; startedAt?: string | null; completedAt?: string | null;
  progress: { completed: number; total: number }; results: Result[];
  auditEventIds: string[]; capabilities: { canCancel: boolean };
};

export default function EvaluationDetailPage() {
  const id = String(useParams().id);
  const [data, setData] = useState<Detail | null>(null);
  const [error, setError] = useState("");
  const [cancelling, setCancelling] = useState(false);
  const inflight = useRef(false);
  const load = useCallback(async () => {
    if (inflight.current) return;
    inflight.current = true;
    try { setData(await api<Detail>(`/api/v1/evaluation/runs/${id}`)); setError(""); }
    catch (err) { setError(err instanceof Error ? err.message : "Unable to load evaluation"); }
    finally { inflight.current = false; }
  }, [id]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!data || !isExecutionActive(data.status)) return;
    const timer = window.setInterval(() => void load(), 2500);
    return () => window.clearInterval(timer);
  }, [data, load]);

  async function cancel() {
    setCancelling(true);
    try { setData(await api<Detail>(`/api/v1/evaluation/runs/${id}/cancel`, { method: "POST" })); }
    catch (err) { setError(err instanceof Error ? err.message : "Unable to cancel evaluation"); }
    finally { setCancelling(false); }
  }

  return (
    <Page width="wide">
      <PageHeader
        eyebrow="Evaluation detail"
        title={data ? data.suiteName : "Evaluation"}
        description="Check explanations are derived from persisted observable evidence. Private model reasoning is neither captured nor displayed."
        status={data && <StatusBadge status={data.status} />}
        actions={data?.capabilities.canCancel && <Button variant="danger" loading={cancelling} loadingLabel="Cancelling…" onClick={() => void cancel()}>Cancel</Button>}
      />
      <EvaluationNav />
      {error && <ErrorState title="Unable to load evaluation" onRetry={() => void load()}>{error}</ErrorState>}
      {!data && !error && <Skeleton className="h-48" />}
      {data && (
        <>
          <Card>
            <SectionHeader title="Pinned configuration" description="Activation changes cannot mutate this record." />
            <dl className="mt-3 grid gap-3 text-sm sm:grid-cols-2 xl:grid-cols-4">
              <Item label="Evaluation ID"><Mono>{data.id}</Mono></Item>
              <Item label="Suite">{data.suiteKey} v{data.suiteVersion}</Item>
              <Item label="Agent">{data.agentName} v{data.agentVersion}</Item>
              <Item label="Knowledge">{data.knowledgeName} v{data.knowledgeVersion}</Item>
              <Item label="Model">{data.provider} / {data.model}</Item>
              <Item label="Evaluator"><Mono>{data.evaluatorVersion}</Mono></Item>
              <Item label="Progress">{data.progress.completed} of {data.progress.total}</Item>
              <Item label="Started"><Timestamp value={data.startedAt || data.createdAt} /></Item>
            </dl>
            <div className="mt-3 flex flex-wrap gap-2">
              {data.traceId && <ButtonLink href={`/observability/traces/${data.traceId}`} variant="ghost">Trace</ButtonLink>}
              {data.auditEventIds[0] && <ButtonLink href={`/audit/${data.auditEventIds[0]}`} variant="ghost">Audit event</ButtonLink>}
            </div>
          </Card>
          <div className="space-y-3">
            {data.results.map((result) => (
              <Card key={result.id}>
                <div className="flex flex-wrap items-center gap-2">
                  <StatusBadge status={result.status} />
                  <h2 className="font-medium">{result.caseName}</h2>
                  <Mono>{result.category}</Mono>
                  <span className="text-xs text-muted">case v{result.caseVersion}</span>
                  {result.runId && <Link className="ml-auto text-sm text-info hover:underline" href={`/runs/${result.runId}`}>Product run</Link>}
                </div>
                {result.explanation && <p className="mt-2 text-sm text-muted">{result.explanation}</p>}
                {result.failureCategory && <p className="mt-1 text-sm text-danger">{result.failureCategory}</p>}
                <div className="mt-3 space-y-2">
                  {result.checks.map((check) => (
                    <details key={check.key} className="rounded-md border border-line p-3">
                      <summary className="cursor-pointer list-none">
                        <span className="inline-flex items-center gap-2"><StatusBadge status={check.status} /><Mono>{check.key}</Mono><span className="text-sm text-muted">{check.explanation}</span></span>
                      </summary>
                      <div className="mt-3"><CodeBlock value={JSON.stringify(check.evidence, null, 2)} /></div>
                    </details>
                  ))}
                </div>
              </Card>
            ))}
          </div>
        </>
      )}
    </Page>
  );
}

function Item({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><dt className="text-xs uppercase tracking-wide text-muted">{label}</dt><dd className="mt-1 min-w-0 break-words">{children}</dd></div>;
}
