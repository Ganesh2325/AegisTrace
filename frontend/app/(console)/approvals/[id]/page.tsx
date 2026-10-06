"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { api, roleOf } from "../../../../lib/api";
import { canAccess } from "../../../../lib/access";
import { readSession } from "../../../../lib/session";
import {
  DETAIL_POLL_MS,
  decisionMessage,
  needsApproveConfirmation,
  toolLabel,
  type ApprovalDetail,
} from "../../../../lib/approvals";
import { Button, ButtonLink } from "../../../../components/ui/Button";
import { Card } from "../../../../components/ui/Card";
import { TextAreaField } from "../../../../components/ui/Field";
import { Dialog } from "../../../../components/ui/Overlay";
import { Page, PageHeader, SectionHeader } from "../../../../components/ui/PageHeader";
import { RiskBadge } from "../../../../components/ui/RiskBadge";
import { StatusBadge } from "../../../../components/ui/StatusBadge";
import { EmptyState, ErrorState, Skeleton } from "../../../../components/ui/States";
import { CodeBlock, Mono, Timestamp } from "../../../../components/ui/Type";
import { useToast } from "../../../../components/ui/Toast";

export default function ApprovalDetailPage() {
  const params = useParams<{ id: string }>();
  const role = roleOf(readSession().current);
  const canReview = canAccess(role, "approvals.review");
  const toast = useToast();
  const [row, setRow] = useState<ApprovalDetail | null>(null);
  const [error, setError] = useState("");
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState("");
  const [confirm, setConfirm] = useState<"approve" | "reject" | null>(null);
  const [rejectReason, setRejectReason] = useState("");
  const [actionError, setActionError] = useState("");

  function load() {
    api<ApprovalDetail>(`/api/v1/approvals/${params.id}`)
      .then((body) => { setError(""); setRow(body); })
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load this approval"))
      .finally(() => setReady(true));
  }

  useEffect(() => { load(); }, [params.id]);
  useEffect(() => {
    if (!row) return;
    const open = row.status === "PENDING" || (row.status === "APPROVED" && !row.executionStatus);
    if (!open) return;
    const timer = window.setInterval(load, DETAIL_POLL_MS);
    return () => window.clearInterval(timer);
  }, [row?.status, row?.executionStatus, params.id]);

  async function decide(approve: boolean) {
    if (busy || !row) return;
    if (!approve && !rejectReason.trim()) {
      setActionError("A rejection reason is required.");
      return;
    }
    setBusy(approve ? "approve" : "reject");
    setActionError("");
    try {
      const body = await api<ApprovalDetail>(`/api/v1/approvals/${row.id}/${approve ? "approve" : "reject"}`, {
        method: "POST",
        body: JSON.stringify({ reason: approve ? "" : rejectReason.trim() }),
      });
      setRow(body);
      setConfirm(null);
      toast("success", decisionMessage(body.status));
    } catch (err) {
      const message = err instanceof Error ? err.message : "Decision failed";
      setActionError(message);
      toast("error", message);
      load();
    } finally {
      setBusy("");
    }
  }

  if (error && !row) return <ErrorState title="Unable to load this approval" onRetry={load}>{error}</ErrorState>;
  if (!ready || !row) return <Page width="wide"><Skeleton className="h-40" /></Page>;

  const pending = row.status === "PENDING";
  const showApprove = canReview && pending && row.canDecide;
  const confirmApprove = needsApproveConfirmation(row.risk, row.classification);

  return (
    <Page width="wide">
      <PageHeader
        eyebrow="Approvals"
        title={toolLabel(row.tool)}
        description="Review the exact queued action. Policy already required a human decision."
        status={<StatusBadge status={row.status} />}
        actions={<ButtonLink href="/approvals" variant="ghost">Back to approvals</ButtonLink>}
      />
      {row.selfRequested && (
        <Card variant="warning">
          <p className="text-sm text-paper">You requested this action</p>
          <p className="mt-1 text-sm text-muted">Reviewers cannot approve their own request. An administrator can still decide.</p>
        </Card>
      )}
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,0.8fr)]">
        <div className="space-y-4">
          <Card>
            <SectionHeader title="Action" />
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <Mono>{row.tool}</Mono>
              {row.classification && <StatusBadge status={row.classification} label={row.classification.replace("_", " ")} />}
              <RiskBadge level={row.risk} />
            </div>
            <p className="mt-3 text-sm text-muted">{row.whatWillHappen}</p>
          </Card>
          <Card>
            <SectionHeader title="Policy decision" description="Calculated by the control plane, not the model." />
            <p className="mt-2 text-sm text-paper">{row.policyDecision}</p>
            {row.policyReason && <p className="mt-1 text-sm text-muted">{row.policyReason}</p>}
            {row.policyCode && <p className="mt-1"><Mono>{row.policyCode}</Mono></p>}
            <p className="mt-3 text-xs text-muted">Retrieved evidence informs the proposal. Policy controls authorization. A reviewer decides whether to proceed.</p>
          </Card>
          <Card>
            <SectionHeader title="Arguments" description="Stored proposal payload. Reviewers cannot edit this action." />
            {row.includeArguments && row.arguments ? (
              <div className="mt-3"><CodeBlock value={JSON.stringify(row.arguments, null, 2)} /></div>
            ) : (
              <p className="mt-2 text-sm text-muted">Tool arguments are withheld for this role.</p>
            )}
          </Card>
          <Card>
            <SectionHeader title="Evidence" description="Citations from the run. Evidence does not authorize the tool." />
            {row.citations.length === 0 && <p className="mt-2 text-sm text-muted">No citations stored on this run.</p>}
            <ul className="mt-2 space-y-2">
              {row.citations.map((citation, index) => (
                <li key={citation.chunkId || index}>
                  <Card variant="compact">
                    <div className="text-sm text-warning">{citation.documentTitle} {citation.section ? `· ${citation.section}` : ""}</div>
                    <p className="mt-1 text-sm text-muted">{citation.quote}</p>
                  </Card>
                </li>
              ))}
            </ul>
          </Card>
        </div>
        <div className="space-y-4">
          <Card>
            <SectionHeader title="Context" />
            <dl className="mt-3 grid gap-3 text-sm">
              <div><dt className="text-[11px] uppercase tracking-[0.12em] text-muted">Requested by</dt><dd>{row.requesterEmail}</dd></div>
              <div><dt className="text-[11px] uppercase tracking-[0.12em] text-muted">Agent</dt><dd>{row.agentName} {row.agentVersion != null ? `v${row.agentVersion}` : ""}</dd></div>
              {row.knowledgeName && (
                <div><dt className="text-[11px] uppercase tracking-[0.12em] text-muted">Knowledge</dt><dd>{row.knowledgeName} {row.knowledgeVersion != null ? `v${row.knowledgeVersion}` : ""}</dd></div>
              )}
              <div><dt className="text-[11px] uppercase tracking-[0.12em] text-muted">Run</dt><dd><ButtonLink href={`/runs/${row.runId}`} variant="ghost"><Mono>{row.runId}</Mono></ButtonLink></dd></div>
              <div><dt className="text-[11px] uppercase tracking-[0.12em] text-muted">Run state</dt><dd><StatusBadge status={row.runState} /></dd></div>
              <div><dt className="text-[11px] uppercase tracking-[0.12em] text-muted">Trace</dt><dd><Mono>{row.traceId}</Mono></dd></div>
              <div><dt className="text-[11px] uppercase tracking-[0.12em] text-muted">Requested</dt><dd><Timestamp value={row.requestedAt} /></dd></div>
              <div><dt className="text-[11px] uppercase tracking-[0.12em] text-muted">Expires</dt><dd><Timestamp value={row.expiresAt} /></dd></div>
              {row.reviewerEmail && <div><dt className="text-[11px] uppercase tracking-[0.12em] text-muted">Reviewer</dt><dd>{row.reviewerEmail}</dd></div>}
              {row.decidedAt && <div><dt className="text-[11px] uppercase tracking-[0.12em] text-muted">Decided</dt><dd><Timestamp value={row.decidedAt} /></dd></div>}
              {row.decisionReason && <div><dt className="text-[11px] uppercase tracking-[0.12em] text-muted">Reason</dt><dd>{row.decisionReason}</dd></div>}
              {row.executionStatus && <div><dt className="text-[11px] uppercase tracking-[0.12em] text-muted">Execution</dt><dd><StatusBadge status={row.executionStatus} /></dd></div>}
              {row.ticketId && <div><dt className="text-[11px] uppercase tracking-[0.12em] text-muted">Ticket</dt><dd><Mono>{row.ticketId}</Mono></dd></div>}
            </dl>
          </Card>
          <Card>
            <SectionHeader title="Timeline" description="Stored run events only." />
            {row.timeline.length === 0 && <EmptyState title="No timeline events yet">Events appear as the run progresses.</EmptyState>}
            <ol className="mt-3 space-y-2">
              {row.timeline.map((event) => (
                <li key={event.sequence} className="flex items-center justify-between gap-2 text-sm">
                  <span>{event.eventType.replaceAll("_", " ").toLowerCase()}</span>
                  <Timestamp value={event.createdAt} />
                </li>
              ))}
            </ol>
          </Card>
          {actionError && <ErrorState title="Decision not applied">{actionError}</ErrorState>}
          {showApprove && (
            <div className="flex flex-wrap gap-2">
              <Button variant="danger" disabled={!!busy} onClick={() => { setActionError(""); setConfirm("reject"); }}>Reject action</Button>
              <Button loading={busy === "approve"} loadingLabel="Saving…" onClick={() => {
                if (confirmApprove) { setConfirm("approve"); return; }
                void decide(true);
              }}>Approve action</Button>
            </div>
          )}
          {pending && canReview && !row.canDecide && (
            <p className="text-sm text-muted">You cannot decide this approval.</p>
          )}
          {!pending && (
            <Card variant={row.status === "APPROVED" ? "success" : row.status === "REJECTED" ? "danger" : "warning"}>
              <p className="text-sm font-medium text-paper">{row.status === "APPROVED" ? "Approved" : row.status === "REJECTED" ? "Rejected" : row.status}</p>
              {row.reviewerEmail && <p className="mt-1 text-sm text-muted">Reviewer: {row.reviewerEmail}</p>}
              {row.decidedAt && <p className="mt-1 text-sm text-muted">Time: <Timestamp value={row.decidedAt} /></p>}
              {row.status === "REJECTED" && <p className="mt-1 text-sm">No tool execution occurred for this rejection unless a later worker row says otherwise. Current execution: {row.executionStatus || "none"}.</p>}
              {row.status === "APPROVED" && !row.ticketId && <p className="mt-1 text-sm text-muted">Waiting for the worker. Ticket creation is not claimed until execution succeeds.</p>}
            </Card>
          )}
        </div>
      </div>
      <Dialog
        open={confirm === "approve"}
        title="Approve this action?"
        description="The queued tool operation will be allowed to continue if the run remains executable."
        confirmLabel="Approve action"
        loading={busy === "approve"}
        error={actionError}
        onClose={() => { if (!busy) setConfirm(null); }}
        onConfirm={() => void decide(true)}
      />
      <Dialog
        open={confirm === "reject"}
        title="Reject this action?"
        description="The tool will not execute."
        confirmLabel="Reject action"
        danger
        loading={busy === "reject"}
        error={actionError}
        onClose={() => { if (!busy) setConfirm(null); }}
        onConfirm={() => void decide(false)}
      >
        <TextAreaField label="Reason" value={rejectReason} onChange={(event) => setRejectReason(event.target.value)} required />
      </Dialog>
    </Page>
  );
}
