"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api } from "../../../../lib/api";
import { type EvaluationRun, progressText } from "../../../../lib/evaluations";
import { Button } from "../../../../components/ui/Button";
import { Card } from "../../../../components/ui/Card";
import { DataTable } from "../../../../components/ui/DataTable";
import { Page, PageHeader } from "../../../../components/ui/PageHeader";
import { EmptyState, ErrorState } from "../../../../components/ui/States";
import { StatusBadge } from "../../../../components/ui/StatusBadge";
import { Timestamp } from "../../../../components/ui/Type";
import { EvaluationNav } from "../EvaluationNav";

type RunPage = { items: EvaluationRun[]; page: number; total: number; size: number };

export default function EvaluationRunsPage() {
  const [data, setData] = useState<RunPage | null>(null);
  const [error, setError] = useState("");
  const [page, setPage] = useState(0);
  const load = useCallback(async () => {
    try {
      setData(await api<RunPage>(`/api/v1/evaluation/runs?page=${page}`));
      setError("");
    } catch (err) { setError(err instanceof Error ? err.message : "Unable to load evaluation history"); }
  }, [page]);
  useEffect(() => { void load(); }, [load]);
  return (
    <Page width="wide">
      <PageHeader eyebrow="Reproducible history" title="Evaluation runs" description="Every execution records its exact suite, agent version, knowledge version, model, and evaluator version." />
      <EvaluationNav />
      {error && <ErrorState title="Unable to load evaluation history" onRetry={() => void load()}>{error}</ErrorState>}
      <div className="hidden md:block">
        <DataTable
          loading={!data && !error}
          rows={data?.items || []}
          getKey={(row) => row.id}
          columns={[
            { key: "suite", header: "Suite", cell: (row) => <Link className="text-info hover:underline" href={`/evaluations/runs/${row.id}`}>{row.suiteName} v{row.suiteVersion}</Link> },
            { key: "status", header: "Status", cell: (row) => <StatusBadge status={row.status} /> },
            { key: "progress", header: "Progress", cell: (row) => progressText(row) },
            { key: "target", header: "Target", cell: (row) => <span>Agent v{row.agentVersion} · Knowledge v{row.knowledgeVersion}</span> },
            { key: "time", header: "Started", cell: (row) => <Timestamp value={row.createdAt} /> },
          ]}
          empty={<EmptyState title="No evaluations">Start an evaluation from the Overview.</EmptyState>}
          pagination={data && <Pagination page={page} total={data.total} size={data.size} onPage={setPage} />}
        />
      </div>
      <div className="space-y-3 md:hidden">
        {!data && !error && <p className="text-sm text-muted">Loading history…</p>}
        {data?.items.length === 0 && <EmptyState title="No evaluations">Start an evaluation from the Overview.</EmptyState>}
        {data?.items.map((row) => (
          <Card key={row.id}>
            <div className="flex items-center justify-between gap-2"><Link className="text-info" href={`/evaluations/runs/${row.id}`}>{row.suiteName}</Link><StatusBadge status={row.status} /></div>
            <p className="mt-2 text-sm text-muted">{progressText(row)} cases · Agent v{row.agentVersion} · Knowledge v{row.knowledgeVersion}</p>
          </Card>
        ))}
        {data && data.total > data.size && <Pagination page={page} total={data.total} size={data.size} onPage={setPage} />}
      </div>
    </Page>
  );
}

function Pagination({ page, total, size, onPage }: { page: number; total: number; size: number; onPage: (page: number) => void }) {
  return <div className="flex items-center justify-between"><span>Page {page + 1} · {total} total</span><div className="flex gap-2"><Button variant="ghost" disabled={page === 0} onClick={() => onPage(page - 1)}>Previous</Button><Button variant="ghost" disabled={(page + 1) * size >= total} onClick={() => onPage(page + 1)}>Next</Button></div></div>;
}
