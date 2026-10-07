"use client";

import { FormEvent, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api, ApiError, roleOf } from "../../../lib/api";
import { canAccess } from "../../../lib/access";
import { readSession } from "../../../lib/session";
import {
  ALLOWED_UPLOAD,
  DEFAULT_DOC_PAGE,
  MAX_UPLOAD_LABEL,
  fileKind,
  formatBytes,
  knowledgeVersionLabel,
  scoreLabel,
  type DocumentPage,
  type KnowledgeBase,
  type KnowledgeDocument,
  type KnowledgeVersion,
  type RetrievalPreview,
} from "../../../lib/knowledge";
import { Button } from "../../../components/ui/Button";
import { Card } from "../../../components/ui/Card";
import { Dialog } from "../../../components/ui/Overlay";
import { SelectField, TextAreaField, TextField } from "../../../components/ui/Field";
import { MetricCard } from "../../../components/ui/MetricCard";
import { Page, PageHeader, SectionHeader } from "../../../components/ui/PageHeader";
import { StatusBadge } from "../../../components/ui/StatusBadge";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/States";
import { DataTable } from "../../../components/ui/DataTable";
import { Mono } from "../../../components/ui/Type";

export default function KnowledgePage() {
  const role = roleOf(readSession().current);
  const canManage = canAccess(role, "knowledge.manage");
  const router = useRouter();
  const [bases, setBases] = useState<KnowledgeBase[]>([]);
  const [docs, setDocs] = useState<DocumentPage | null>(null);
  const [versions, setVersions] = useState<KnowledgeVersion[]>([]);
  const [error, setError] = useState("");
  const [ready, setReady] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [status, setStatus] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [publishOpen, setPublishOpen] = useState(false);
  const [pending, setPending] = useState("");
  const [formError, setFormError] = useState("");
  const [title, setTitle] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [inspectQuery, setInspectQuery] = useState("Why was my application rejected?");
  const [inspectVersion, setInspectVersion] = useState("");
  const [preview, setPreview] = useState<RetrievalPreview | null>(null);
  const [previewError, setPreviewError] = useState("");

  const base = bases[0];
  const processing = docs?.items.some((doc) => doc.status === "UPLOADED" || doc.status === "PROCESSING");

  function load(signal?: AbortSignal) {
    api<KnowledgeBase[]>("/api/v1/knowledge-bases", { signal }).then(async (rows) => {
      setBases(rows);
      const selected = rows[0];
      if (!selected) {
        setDocs({ items: [], total: 0, page: 0, size: DEFAULT_DOC_PAGE });
        setVersions([]);
        return;
      }
      const params = new URLSearchParams({ page: String(page), size: String(DEFAULT_DOC_PAGE) });
      if (status) params.set("status", status);
      if (query.trim()) params.set("q", query.trim());
      const [pageBody, versionRows] = await Promise.all([
        api<DocumentPage>(`/api/v1/knowledge-bases/${selected.id}/documents?${params}`, { signal }),
        api<KnowledgeVersion[]>(`/api/v1/knowledge-bases/${selected.id}/versions`, { signal }),
      ]);
      setDocs(pageBody);
      setVersions(versionRows);
      if (!inspectVersion && selected.currentVersionId) setInspectVersion(selected.currentVersionId);
    }).catch((err) => {
      if (!(err instanceof DOMException && err.name === "AbortError")) setError(err.message);
    }).finally(() => {
      if (!signal?.aborted) setReady(true);
    });
  }

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [attempt, page, status]);

  useEffect(() => {
    if (typeof window === "undefined") return;
    if (new URLSearchParams(window.location.search).get("upload") === "1" && canManage) setUploadOpen(true);
  }, [canManage]);

  useEffect(() => {
    if (!processing) return;
    const timer = window.setInterval(() => setAttempt((value) => value + 1), 3000);
    return () => window.clearInterval(timer);
  }, [processing]);

  const summary = useMemo(() => base, [base]);

  async function submitUpload(event: FormEvent) {
    event.preventDefault();
    if (!base || !file) {
      setFormError("Choose a markdown, text, or PDF file.");
      return;
    }
    setPending("upload");
    setFormError("");
    const body = new FormData();
    body.append("file", file);
    if (title.trim()) body.append("title", title.trim());
    try {
      await api(`/api/v1/knowledge-bases/${base.id}/documents`, { method: "POST", body });
      setUploadOpen(false);
      setFile(null);
      setTitle("");
      router.replace("/knowledge");
      setAttempt((value) => value + 1);
    } catch (err) {
      setFormError(err instanceof Error ? err.message : "Upload failed");
    } finally {
      setPending("");
    }
  }

  async function publish() {
    if (!base) return;
    setPending("publish");
    setFormError("");
    try {
      const created = await api<{ id: string }>(`/api/v1/knowledge-bases/${base.id}/versions`, { method: "POST" });
      await api(`/api/v1/knowledge-bases/${base.id}/versions/${created.id}/activate`, { method: "POST" });
      setPublishOpen(false);
      setAttempt((value) => value + 1);
    } catch (err) {
      setFormError(err instanceof Error ? err.message : "Publish failed");
    } finally {
      setPending("");
    }
  }

  async function runPreview(event: FormEvent) {
    event.preventDefault();
    if (!base) return;
    setPending("retrieve");
    setPreviewError("");
    try {
      const result = await api<RetrievalPreview>(`/api/v1/knowledge-bases/${base.id}/retrieval`, {
        method: "POST",
        body: JSON.stringify({
          query: inspectQuery,
          knowledgeBaseVersionId: inspectVersion || undefined,
          topK: 5,
        }),
      });
      setPreview(result);
    } catch (err) {
      setPreview(null);
      setPreviewError(err instanceof ApiError && err.status === 403 ? "You cannot inspect this knowledge base." : (err instanceof Error ? err.message : "Retrieval unavailable"));
    } finally {
      setPending("");
    }
  }

  return (
    <Page width="wide">
      <PageHeader
        eyebrow="Corpus"
        title="Knowledge"
        description="Manage the evidence available to AegisTrace agents. Retrieved documents are data, not authority."
        actions={
          canManage && base ? (
            <>
              <Button variant="secondary" onClick={() => setPublishOpen(true)}>New knowledge version</Button>
              <Button onClick={() => setUploadOpen(true)}>Upload document</Button>
            </>
          ) : null
        }
      />
      {error && <ErrorState title="Unable to load knowledge" onRetry={() => setAttempt((value) => value + 1)}>{error}</ErrorState>}
      {!error && !ready && <Skeleton className="h-24" />}
      {!error && ready && !base && (
        <EmptyState title="No knowledge base configured">A workspace knowledge base is required before documents can be ingested.</EmptyState>
      )}
      {!error && ready && summary && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <MetricCard label="Knowledge base" value={summary.name} context={summary.embeddingModel} />
          <MetricCard label="Active version" value={knowledgeVersionLabel(summary.currentVersion)} />
          <MetricCard label="Documents" value={summary.documentCount} />
          <MetricCard label="Processing" value={summary.processingCount} />
          <MetricCard label="Failed" value={summary.failedCount} tone={summary.failedCount > 0 ? "warning" : "default"} />
        </div>
      )}
      {!error && ready && base && (
        <>
          <Card>
            <SectionHeader title="Documents" description="Metadata search only. Content retrieval is the inspector below." />
            <div className="mt-3 flex flex-col gap-3 sm:flex-row">
              <TextField label="Search titles" value={query} onChange={(event) => setQuery(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") { setPage(0); setAttempt((value) => value + 1); } }} />
              <SelectField label="Status" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }}>
                <option value="">All</option>
                <option value="ACTIVE">Active</option>
                <option value="UPLOADED">Uploaded</option>
                <option value="PROCESSING">Processing</option>
                <option value="FAILED">Failed</option>
                <option value="DISABLED">Disabled</option>
              </SelectField>
            </div>
            <div className="mt-4">
              <DataTable
                rows={docs?.items || []}
                getKey={(doc) => doc.id}
                empty={<EmptyState title="No knowledge documents yet">Upload a policy document to make it available to agents.</EmptyState>}
                columns={[
                  { key: "title", header: "Name", cell: (doc) => <Link className="text-paper underline-offset-2 hover:underline" href={`/knowledge/documents/${doc.id}`}>{doc.title}</Link> },
                  { key: "type", header: "Type", cell: (doc) => fileKind(doc.mediaType) },
                  { key: "status", header: "Status", cell: (doc) => <StatusBadge status={doc.status} /> },
                  { key: "chunks", header: "Chunks", cell: (doc) => doc.status === "ACTIVE" ? doc.chunks : "—" },
                  { key: "emb", header: "Embeddings", cell: (doc) => <StatusBadge status={doc.embeddingStatus} /> },
                  { key: "updated", header: "Uploaded", cell: (doc) => new Date(doc.createdAt).toISOString().slice(0, 10) },
                ]}
              />
            </div>
            {docs && docs.total > docs.size && (
              <div className="mt-3 flex items-center justify-between text-sm text-muted">
                <span>{docs.total} documents</span>
                <div className="flex gap-2">
                  <Button variant="ghost" disabled={page === 0} onClick={() => setPage((value) => Math.max(0, value - 1))}>Previous</Button>
                  <Button variant="ghost" disabled={(page + 1) * docs.size >= docs.total} onClick={() => setPage((value) => value + 1)}>Next</Button>
                </div>
              </div>
            )}
          </Card>
          <Card>
            <SectionHeader title="Knowledge versions" description="Publishing snapshots every ACTIVE document. Agent versions keep the identifier they stored." />
            <ul className="mt-3 space-y-2">
              {versions.map((version) => (
                <li key={version.id} className="flex flex-wrap items-center gap-2 text-sm">
                  <Mono>v{version.version}</Mono>
                  <StatusBadge status={version.current ? "ACTIVE" : "INACTIVE"} label={version.current ? "Current" : "Not current"} />
                  <span className="text-muted">{version.documentCount} documents</span>
                </li>
              ))}
            </ul>
          </Card>
          <Card>
            <SectionHeader title="Retrieval inspector" description="Uses the same ranked retrieval path as Support Run. Higher scores are better. Values are not percentages." />
            <form className="mt-3 space-y-3" onSubmit={runPreview}>
              <TextAreaField label="Query" value={inspectQuery} onChange={(event) => setInspectQuery(event.target.value)} />
              <SelectField label="Knowledge version" value={inspectVersion} onChange={(event) => setInspectVersion(event.target.value)}>
                {versions.map((version) => (
                  <option key={version.id} value={version.id}>v{version.version}{version.current ? " (current)" : ""}</option>
                ))}
              </SelectField>
              <Button type="submit" loading={pending === "retrieve"} loadingLabel="Retrieving…">Retrieve</Button>
            </form>
            {previewError && <ErrorState title="Retrieval unavailable">{previewError}</ErrorState>}
            {preview && (
              <div className="mt-4 space-y-3">
                <p className="text-sm text-muted">
                  {preview.knowledgeBaseName} {knowledgeVersionLabel(preview.knowledgeVersion)} · top {preview.topK} · {preview.latencyMs} ms · {preview.similaritySemantics}
                </p>
                {preview.hits.length === 0 && <EmptyState title="No retrieved chunks">Nothing in this knowledge version ranked for that query.</EmptyState>}
                {preview.hits.map((hit) => (
                  <article key={hit.chunkId} className="rounded-md border border-line p-3">
                    <div className="flex flex-wrap items-center gap-2 text-sm">
                      <Link className="text-paper underline-offset-2 hover:underline" href={`/knowledge/documents/${hit.documentId}`}>{hit.documentTitle}</Link>
                      {hit.section && <span className="text-muted">{hit.section}</span>}
                      {hit.page != null && <span className="text-muted">page {hit.page}</span>}
                      <Mono>{hit.chunkId.slice(0, 8)}</Mono>
                    </div>
                    <p className="mt-2 text-sm text-paper">{hit.quote}</p>
                    <p className="mt-2 text-xs text-muted">fusion {scoreLabel(hit.fusionScore)} · vector {scoreLabel(hit.vectorScore)} · lexical {scoreLabel(hit.lexicalScore)}</p>
                  </article>
                ))}
              </div>
            )}
          </Card>
        </>
      )}
      <Dialog
        open={uploadOpen}
        title="Upload document"
        description={`Markdown, plain text, or PDF. Maximum ${MAX_UPLOAD_LABEL}. Duplicate checksums in this knowledge base are rejected. The file joins the next published version, not the currently published corpus.`}
        onClose={() => setUploadOpen(false)}
      >
        <form className="space-y-3" onSubmit={submitUpload}>
          <TextField label="Title" value={title} onChange={(event) => setTitle(event.target.value)} hint="Optional. Defaults to the file name." />
          <div className="space-y-1.5">
            <label className="block text-sm font-medium text-paper" htmlFor="knowledge-file">File</label>
            <input id="knowledge-file" type="file" accept={ALLOWED_UPLOAD} onChange={(event) => setFile(event.target.files?.[0] || null)} className="w-full text-sm text-paper" />
            {file && <p className="text-xs text-muted">{file.name} · {formatBytes(file.size)}</p>}
          </div>
          {formError && uploadOpen && <p className="text-sm text-danger" role="alert">{formError}</p>}
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => setUploadOpen(false)}>Cancel</Button>
            <Button type="submit" loading={pending === "upload"} loadingLabel="Uploading…">Upload</Button>
          </div>
        </form>
      </Dialog>
      <Dialog
        open={publishOpen}
        title="Publish knowledge version"
        description="Creates a snapshot of every ACTIVE embedded document and makes it the current version for future agent versions. Existing agent versions keep their pinned knowledge version."
        confirmLabel="Publish and activate"
        onConfirm={publish}
        onClose={() => setPublishOpen(false)}
        loading={pending === "publish"}
        error={publishOpen ? formError : undefined}
      />
    </Page>
  );
}
