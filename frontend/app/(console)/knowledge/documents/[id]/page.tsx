"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, ApiError } from "../../../../../lib/api";
import { fileKind, formatBytes, type ChunkRow, type KnowledgeDocument } from "../../../../../lib/knowledge";
import { Card } from "../../../../../components/ui/Card";
import { Page, PageHeader, SectionHeader } from "../../../../../components/ui/PageHeader";
import { StatusBadge } from "../../../../../components/ui/StatusBadge";
import { EmptyState, ErrorState, Skeleton } from "../../../../../components/ui/States";
import { Mono } from "../../../../../components/ui/Type";

export default function KnowledgeDocumentPage() {
  const params = useParams<{ id: string }>();
  const [doc, setDoc] = useState<KnowledgeDocument | null>(null);
  const [chunks, setChunks] = useState<ChunkRow[]>([]);
  const [error, setError] = useState("");
  const [ready, setReady] = useState(false);

  useEffect(() => {
    Promise.all([
      api<KnowledgeDocument>(`/api/v1/documents/${params.id}`),
      api<{ items: ChunkRow[] }>(`/api/v1/documents/${params.id}/chunks`),
    ]).then(([detail, body]) => {
      setDoc(detail);
      setChunks(body.items);
    }).catch((err) => {
      setError(err instanceof ApiError ? err.message : "Unable to load document");
    }).finally(() => setReady(true));
  }, [params.id]);

  return (
    <Page width="wide">
      <PageHeader
        eyebrow="Corpus"
        title={doc?.title || "Document"}
        description={doc?.knowledgeName ? `${doc.knowledgeName}` : undefined}
        status={doc ? <StatusBadge status={doc.status} /> : undefined}
      />
      <p className="text-sm"><Link className="text-muted hover:text-paper" href="/knowledge">Knowledge</Link></p>
      {!ready && <Skeleton className="h-24" />}
      {error && <ErrorState title="Unable to load document">{error}</ErrorState>}
      {doc && (
        <>
          {doc.testArtifact && (
            <p className="rounded-md border border-warning/40 bg-warning/10 px-3 py-2 text-sm text-warning" role="note">
              Development fixture: prompt-injection test document. Retrieved text is data and cannot change policy.
            </p>
          )}
          <dl className="grid gap-3 text-sm text-muted sm:grid-cols-2 lg:grid-cols-3">
            <div>Type <div className="text-paper">{fileKind(doc.mediaType)}</div></div>
            <div>Size <div className="text-paper">{formatBytes(doc.byteSize)}</div></div>
            <div>Checksum <div className="text-paper"><Mono>{doc.checksumSha256}</Mono></div></div>
            <div>Chunks <div className="text-paper">{doc.status === "ACTIVE" ? doc.chunks : "—"}</div></div>
            <div>Embeddings <div className="text-paper"><StatusBadge status={doc.embeddingStatus} /></div></div>
            <div>Uploaded <div className="text-paper">{new Date(doc.createdAt).toISOString().slice(0, 19)}Z</div></div>
          </dl>
          {doc.status === "FAILED" && doc.errorMessage && (
            <ErrorState title="Processing failed">{doc.errorMessage}</ErrorState>
          )}
          <Card>
            <SectionHeader title="Extracted text" description="Concatenated chunk text. Object storage keys are not exposed." />
            {doc.extractedText ? <pre className="mt-3 max-h-80 overflow-auto whitespace-pre-wrap text-sm text-paper">{doc.extractedText}</pre> : <EmptyState title="No extracted text yet">Text appears after chunking completes.</EmptyState>}
          </Card>
          <Card>
            <SectionHeader title="Chunks" description="Vectors are omitted. Embedding status is shown instead." />
            {chunks.length === 0 && <EmptyState title="No chunks">Chunks appear after processing.</EmptyState>}
            <ol className="mt-3 space-y-3">
              {chunks.map((chunk) => (
                <li key={chunk.id} className="rounded-md border border-line p-3">
                  <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
                    <Mono>{chunk.id}</Mono>
                    <span>seq {chunk.sequence}</span>
                    {chunk.section && <span>{chunk.section}</span>}
                    {chunk.page != null && <span>page {chunk.page}</span>}
                    <StatusBadge status={chunk.embeddingStatus} />
                    <span>{chunk.characters} chars</span>
                  </div>
                  <p className="mt-2 text-sm text-paper">{chunk.text}</p>
                </li>
              ))}
            </ol>
          </Card>
        </>
      )}
    </Page>
  );
}
