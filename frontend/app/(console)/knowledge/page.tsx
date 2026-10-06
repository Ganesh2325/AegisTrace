"use client";

import { useEffect, useState } from "react";
import { api } from "../../../lib/api";
import { DataTable } from "../../../components/ui/DataTable";
import { Page, PageHeader } from "../../../components/ui/PageHeader";
import { StatusBadge } from "../../../components/ui/StatusBadge";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/States";
import { Mono } from "../../../components/ui/Type";

type Base = { id: string; name: string; slug: string; embeddingModel: string; status: string };
type Doc = { id: string; title: string; status: string; chunks: number; errorMessage?: string };

export default function KnowledgePage() {
  const [bases, setBases] = useState<Base[]>([]);
  const [docs, setDocs] = useState<Doc[]>([]);
  const [error, setError] = useState("");
  const [ready, setReady] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let stop = false;
    api<Base[]>("/api/v1/knowledge-bases").then(async (rows) => {
      if (stop) return;
      setBases(rows);
      if (rows[0]) setDocs(await api<Doc[]>(`/api/v1/knowledge-bases/${rows[0].id}/documents`));
    }).catch((err) => { if (!stop) setError(err.message); }).finally(() => { if (!stop) setReady(true); });
    return () => { stop = true; };
  }, [attempt]);

  return (
    <Page width="wide">
      <PageHeader eyebrow="Corpus" title="Knowledge" description="Documents are data. A document that tells the agent to ignore policy does not change approval." />
      {error && <ErrorState title="Unable to load knowledge" onRetry={() => setAttempt((value) => value + 1)}>{error}</ErrorState>}
      {bases.map((base) => (
        <div key={base.id} className="flex flex-wrap items-center gap-2 text-sm">
          <span>{base.name}</span>
          <Mono>{base.embeddingModel}</Mono>
          <StatusBadge status={base.status} />
        </div>
      ))}
      {!error && !ready && <Skeleton className="h-24" />}
      {!error && ready && (
        <DataTable
          rows={docs}
          getKey={(doc) => doc.id}
          empty={<EmptyState title="No documents yet">The worker seeds the synthetic corpus after startup.</EmptyState>}
          columns={[
            { key: "title", header: "Title", cell: (doc) => doc.title },
            { key: "status", header: "Status", cell: (doc) => <StatusBadge status={doc.status} /> },
            { key: "chunks", header: "Chunks", cell: (doc) => doc.chunks },
          ]}
        />
      )}
    </Page>
  );
}
