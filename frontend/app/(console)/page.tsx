"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api } from "../../lib/api";

type Summary = {
  runs: number; completed: number; failed: number; completionRate: number; failureRate: number;
  p50Ms: number; p95Ms: number; p99Ms: number; tokens: number; estimatedCostUsd: number;
  pendingApprovals: number; policyDenialRate: number; approvalWaitMs: number; queueDepth: number;
  evaluationPassRate: number;
};

export default function Dashboard() {
  const [summary, setSummary] = useState<Summary | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    api<Summary>("/api/v1/metrics/summary").then(setSummary).catch((err) => setError(err.message));
  }, []);

  return (
    <div>
      <p className="kicker">Workspace</p>
      <h1 className="text-2xl font-semibold">Operations</h1>
      <p className="mt-1 max-w-2xl text-sm text-mist">Numbers come from stored runs. An empty workspace shows zeros, not sample charts.</p>
      {error && <p className="mt-4 text-rose">{error}</p>}
      {!summary && !error && <div className="mt-6 h-24 animate-pulse rounded-md bg-panel" />}
      {summary && (
        <div className="mt-6 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          <Stat label="Runs" value={summary.runs} />
          <Stat label="Completion" value={`${Math.round(summary.completionRate * 100)}%`} />
          <Stat label="Failure" value={`${Math.round(summary.failureRate * 100)}%`} />
          <Stat label="Pending approvals" value={summary.pendingApprovals} />
          <Stat label="p50" value={`${Math.round(summary.p50Ms)} ms`} />
          <Stat label="p95" value={`${Math.round(summary.p95Ms)} ms`} />
          <Stat label="p99" value={`${Math.round(summary.p99Ms)} ms`} />
          <Stat label="Approval wait" value={`${Math.round(summary.approvalWaitMs)} ms`} />
          <Stat label="Policy denial rate" value={`${Math.round(summary.policyDenialRate * 100)}%`} />
          <Stat label="Tokens" value={summary.tokens} />
          <Stat label="Estimated cost" value={`$${Number(summary.estimatedCostUsd).toFixed(4)}`} />
          <Stat label="Queue depth" value={summary.queueDepth} />
        </div>
      )}
      <Link className="btn-primary mt-6" href="/runs/new">New support run</Link>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="panel p-4">
      <div className="kicker">{label}</div>
      <div className="mt-2 font-mono text-2xl">{value}</div>
    </div>
  );
}
