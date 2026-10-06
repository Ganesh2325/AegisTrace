"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api } from "../../../lib/api";
import { Card } from "../../../components/ui/Card";
import { Page, PageHeader } from "../../../components/ui/PageHeader";
import { StatusBadge } from "../../../components/ui/StatusBadge";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/States";
import { CodeBlock, Mono } from "../../../components/ui/Type";

type Evaluation = { id: string; runId: string; model: string; evaluatorVersion: string; datasetVersion: string; passed: boolean; scores: Record<string, number> };

export default function EvaluationsPage() {
  const [rows, setRows] = useState<Evaluation[]>([]);
  const [error, setError] = useState("");
  const [ready, setReady] = useState(false);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let stop = false;
    api<Evaluation[]>("/api/v1/evaluations").then((items) => { if (!stop) setRows(items); }).catch((err) => { if (!stop) setError(err.message); }).finally(() => { if (!stop) setReady(true); });
    return () => { stop = true; };
  }, [attempt]);
  return (
    <Page width="wide">
      <PageHeader
        eyebrow="Heuristic evaluator v1"
        title="Evaluation"
        description="Scores check that citations are substrings of retrieved chunks, that a ticket has an approval, and that a run did not create two tickets. They are not a human labeling study."
      />
      {error && <ErrorState title="Unable to load evaluations" onRetry={() => setAttempt((value) => value + 1)}>{error}</ErrorState>}
      {!error && !ready && <Skeleton className="h-24" />}
      {!error && ready && rows.length === 0 && <EmptyState title="No evaluations yet">A finished run records one heuristic-v1 result. That result is a check, not a benchmark score.</EmptyState>}
      <div className="space-y-3">
        {rows.map((row) => (
          <Card key={row.id}>
            <div className="flex items-center justify-between gap-3">
              <Link className="text-info hover:underline" href={`/runs/${row.runId}`}><Mono>{row.runId}</Mono></Link>
              <StatusBadge status={row.passed ? "COMPLETED" : "FAILED"} label={row.passed ? "passed" : "failed"} />
            </div>
            <p className="mt-2"><Mono>{row.model} · {row.datasetVersion} · {row.evaluatorVersion}</Mono></p>
            <div className="mt-2"><CodeBlock value={JSON.stringify(row.scores, null, 2)} /></div>
          </Card>
        ))}
      </div>
    </Page>
  );
}
