"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { api, ApiError, roleOf } from "../../../lib/api";
import { canAccess } from "../../../lib/access";
import { readSession } from "../../../lib/session";
import {
  APPROVAL_RISKS,
  APPROVAL_STATUSES,
  LIST_POLL_MS,
  toolLabel,
  type ApprovalListItem,
  type ApprovalPage,
} from "../../../lib/approvals";
import { Button, ButtonLink } from "../../../components/ui/Button";
import { Card } from "../../../components/ui/Card";
import { SelectField, TextField } from "../../../components/ui/Field";
import { MetricCard } from "../../../components/ui/MetricCard";
import { Page, PageHeader, SectionHeader } from "../../../components/ui/PageHeader";
import { RiskBadge } from "../../../components/ui/RiskBadge";
import { StatusBadge } from "../../../components/ui/StatusBadge";
import { DataTable } from "../../../components/ui/DataTable";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/States";
import { Mono, Timestamp } from "../../../components/ui/Type";

export default function ApprovalsPage() {
  const role = roleOf(readSession().current);
  const canReview = canAccess(role, "approvals.review");
  const [page, setPage] = useState<ApprovalPage | null>(null);
  const [error, setError] = useState("");
  const [ready, setReady] = useState(false);
  const [status, setStatus] = useState("PENDING");
  const [risk, setRisk] = useState("");
  const [q, setQ] = useState("");
  const [requester, setRequester] = useState("");
  const [agent, setAgent] = useState("");
  const [offset, setOffset] = useState(0);
  const [attempt, setAttempt] = useState(0);

  function load() {
    const params = new URLSearchParams({ page: String(offset), size: "20" });
    if (status) params.set("status", status);
    if (risk) params.set("risk", risk);
    if (q.trim()) params.set("q", q.trim());
    if (requester.trim()) params.set("requester", requester.trim());
    if (agent.trim()) params.set("agent", agent.trim());
    api<ApprovalPage>(`/api/v1/approvals?${params}`)
      .then((body) => { setError(""); setPage(body); })
      .catch((err) => setError(err instanceof ApiError ? err.message : err.message))
      .finally(() => setReady(true));
  }

  useEffect(() => { load(); }, [attempt, offset, status, risk]);
  useEffect(() => {
    const timer = window.setInterval(() => setAttempt((value) => value + 1), LIST_POLL_MS);
    return () => window.clearInterval(timer);
  }, []);

  const summary = page?.summary;
  const pending = summary?.pending ?? 0;
  const rows = page?.items ?? [];
  const pendingRows = useMemo(() => rows.filter((row) => row.status === "PENDING"), [rows]);

  return (
    <Page width="wide">
      <PageHeader
        title="Approvals"
        description="Review sensitive actions requested by AegisTrace agents."
        status={pending > 0 ? <StatusBadge status="PENDING" label={`${pending} pending`} /> : undefined}
      />
      {error && <ErrorState title="Unable to load approvals" onRetry={() => setAttempt((value) => value + 1)}>{error}</ErrorState>}
      {!error && !ready && <Skeleton className="h-24" />}
      {!error && ready && summary && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <MetricCard label="Pending" value={summary.pending} tone={summary.pending > 0 ? "warning" : "default"} hint="Approvals still waiting for a reviewer in this workspace." />
          <MetricCard label="Approved today" value={summary.approvedToday} hint="Decided APPROVED since the start of today in database time." />
          <MetricCard label="Rejected today" value={summary.rejectedToday} hint="Decided REJECTED since the start of today in database time." />
          <MetricCard label="Expired" value={summary.expired} />
          <MetricCard label="Cancelled" value={summary.cancelled} />
        </div>
      )}
      {!error && ready && pending > 0 && (
        <Card variant="warning">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <p className="text-sm font-medium text-paper">{pending === 1 ? "1 pending approval" : `${pending} pending approvals`}</p>
              <p className="mt-1 text-sm text-muted">Highest risk and nearest expiry are listed first.</p>
            </div>
            {pendingRows[0] && <ButtonLink href={`/approvals/${pendingRows[0].id}`}>Review now</ButtonLink>}
          </div>
        </Card>
      )}
      <Card>
        <SectionHeader title="Queue" description={page?.ordering} />
        <div className="mt-3 grid gap-3 md:grid-cols-2 lg:grid-cols-5">
          <TextField label="Search" value={q} onChange={(event) => setQ(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") { setOffset(0); setAttempt((value) => value + 1); } }} hint="Approval ID, run ID, tool, agent, or requester" />
          <SelectField label="Status" value={status} onChange={(event) => { setStatus(event.target.value); setOffset(0); }}>
            <option value="">All</option>
            {APPROVAL_STATUSES.map((item) => <option key={item} value={item}>{item}</option>)}
          </SelectField>
          <SelectField label="Risk" value={risk} onChange={(event) => { setRisk(event.target.value); setOffset(0); }}>
            <option value="">All</option>
            {APPROVAL_RISKS.map((item) => <option key={item} value={item}>{item}</option>)}
          </SelectField>
          <TextField label="Requester" value={requester} onChange={(event) => setRequester(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") { setOffset(0); setAttempt((value) => value + 1); } }} />
          <TextField label="Agent" value={agent} onChange={(event) => setAgent(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") { setOffset(0); setAttempt((value) => value + 1); } }} />
        </div>
        {!error && ready && rows.length === 0 && (
          <div className="mt-4">
            <EmptyState title={status === "PENDING" ? "No pending approvals" : "No approvals match"}>Nothing is waiting in this filter.</EmptyState>
          </div>
        )}
        <ul className="mt-4 space-y-2 md:hidden">
          {rows.map((row) => (
            <li key={row.id}>
              <Card variant="compact">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-sm text-paper">{toolLabel(row.tool)}</span>
                  <StatusBadge status={row.status} />
                </div>
                <div className="mt-1 flex flex-wrap gap-2"><RiskBadge level={row.risk} /></div>
                <p className="mt-1 text-xs text-muted">{row.requesterEmail} · <Timestamp value={row.requestedAt} /></p>
                {canReview && <div className="mt-2"><ButtonLink href={`/approvals/${row.id}`} variant="secondary">Review action</ButtonLink></div>}
              </Card>
            </li>
          ))}
        </ul>
        <div className="mt-4 hidden md:block">
          <DataTable
            rows={rows}
            getKey={(row) => row.id}
            empty={null}
            columns={[
              { key: "action", header: "Action", cell: (row) => <Link className="text-paper underline-offset-2 hover:underline" href={`/approvals/${row.id}`}>{toolLabel(row.tool)}</Link> },
              { key: "tool", header: "Tool", cell: (row) => <Mono>{row.tool}</Mono> },
              { key: "agent", header: "Agent", cell: (row) => <>{row.agentName} {row.agentVersion != null ? `v${row.agentVersion}` : ""}</> },
              { key: "requester", header: "Requester", cell: (row) => row.requesterEmail },
              { key: "risk", header: "Risk", cell: (row) => <RiskBadge level={row.risk} /> },
              { key: "requested", header: "Requested", cell: (row) => <Timestamp value={row.requestedAt} /> },
              { key: "expires", header: "Expires", cell: (row) => <Timestamp value={row.expiresAt} /> },
              { key: "run", header: "Run", cell: (row) => <Mono>{row.runId.slice(0, 8)}</Mono> },
              { key: "status", header: "Status", cell: (row) => <StatusBadge status={row.status} /> },
            ]}
            actions={(row: ApprovalListItem) => canReview ? <ButtonLink href={`/approvals/${row.id}`} variant="secondary">Review</ButtonLink> : null}
          />
        </div>
        {page && page.total > page.size && (
          <div className="mt-3 flex items-center justify-between text-sm text-muted">
            <span>{page.total} approvals</span>
            <div className="flex gap-2">
              <Button variant="ghost" disabled={offset === 0} onClick={() => setOffset((value) => Math.max(0, value - 1))}>Previous</Button>
              <Button variant="ghost" disabled={(offset + 1) * page.size >= page.total} onClick={() => setOffset((value) => value + 1)}>Next</Button>
            </div>
          </div>
        )}
      </Card>
    </Page>
  );
}
