"use client";

import { useEffect, useState } from "react";
import { api } from "../../../lib/api";
import { Button } from "../../../components/ui/Button";
import { Card } from "../../../components/ui/Card";
import { Page, PageHeader } from "../../../components/ui/PageHeader";
import { RiskBadge } from "../../../components/ui/RiskBadge";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/States";
import { CodeBlock, Mono, TextLink } from "../../../components/ui/Type";
import { useToast } from "../../../components/ui/Toast";

type Approval = {
  id: string; status: string; tool: string; arguments: Record<string, string>; reason: string; risk: string;
  requesterEmail: string; policyDecision: string; policyCode: string; traceId: string; runId: string; requiredRole: string;
};

export default function Approvals() {
  const [rows, setRows] = useState<Approval[]>([]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [pendingId, setPendingId] = useState("");
  const [ready, setReady] = useState(false);
  const toast = useToast();

  function load() {
    api<Approval[]>("/api/v1/approvals?status=PENDING").then((items) => { setError(""); setRows(items); }).catch((err) => setError(err.message)).finally(() => setReady(true));
  }
  useEffect(() => { load(); const id = window.setInterval(load, 3000); return () => window.clearInterval(id); }, []);

  async function decide(id: string, approve: boolean) {
    if (pendingId) return;
    setNotice("");
    setPendingId(id);
    try {
      await api(`/api/v1/approvals/${id}/${approve ? "approve" : "reject"}`, { method: "POST", body: JSON.stringify({ reason: approve ? "Reviewed" : "Declined" }) });
      const text = approve ? "Approved. The same run will create one ticket." : "Rejected. No ticket will be created.";
      setNotice(text);
      toast(approve ? "success" : "warning", text);
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Decision failed");
    } finally {
      setPendingId("");
    }
  }

  return (
    <Page width="wide">
      <PageHeader eyebrow="Reviewer" title="Approval queue" description="Pending write actions. Approving resumes the same run and creates one ticket." />
      {error && <ErrorState title="Unable to load approvals" onRetry={load}>{error}</ErrorState>}
      {notice && <p className="text-sm text-success" role="status">{notice}</p>}
      {!error && !ready && <Skeleton className="h-24" />}
      {!error && ready && rows.length === 0 && <EmptyState title="No pending approvals" actionHref="/runs/new" actionLabel="Start a support run">Nothing is waiting for a reviewer.</EmptyState>}
      <div className="space-y-3">
        {rows.map((row) => (
          <Card key={row.id}>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h2 className="font-mono text-sm text-paper">{row.tool}</h2>
              <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
                <span>{row.policyDecision} · {row.requiredRole}</span>
                <RiskBadge level={row.risk} />
              </div>
            </div>
            <p className="mt-2 text-sm">{row.reason}</p>
            <dl className="mt-3 grid gap-3 text-sm md:grid-cols-2">
              <div><dt className="text-[11px] uppercase tracking-[0.12em] text-muted">Requester</dt><dd>{row.requesterEmail}</dd></div>
              <div><dt className="text-[11px] uppercase tracking-[0.12em] text-muted">Policy</dt><dd>{row.policyCode}</dd></div>
              <div><dt className="text-[11px] uppercase tracking-[0.12em] text-muted">Run</dt><dd><TextLink href={`/runs/${row.runId}`}><Mono>{row.runId}</Mono></TextLink></dd></div>
              <div><dt className="text-[11px] uppercase tracking-[0.12em] text-muted">Trace</dt><dd><Mono>{row.traceId}</Mono></dd></div>
            </dl>
            <div className="mt-3"><CodeBlock value={JSON.stringify(row.arguments, null, 2)} /></div>
            <div className="mt-3 flex gap-2">
              <Button loading={pendingId === row.id} loadingLabel="Saving…" onClick={() => decide(row.id, true)}>Approve</Button>
              <Button variant="danger" disabled={pendingId === row.id} onClick={() => decide(row.id, false)}>Reject</Button>
            </div>
          </Card>
        ))}
      </div>
    </Page>
  );
}
