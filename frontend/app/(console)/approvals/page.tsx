"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api } from "../../../lib/api";

type Approval = {
  id: string; status: string; tool: string; arguments: Record<string, string>; reason: string; risk: string;
  requesterEmail: string; policyDecision: string; policyCode: string; traceId: string; runId: string; requiredRole: string;
};

export default function Approvals() {
  const [rows, setRows] = useState<Approval[]>([]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  function load() {
    api<Approval[]>("/api/v1/approvals?status=PENDING").then(setRows).catch((err) => setError(err.message));
  }
  useEffect(() => { load(); const id = window.setInterval(load, 3000); return () => window.clearInterval(id); }, []);

  async function decide(id: string, approve: boolean) {
    setNotice("");
    try {
      await api(`/api/v1/approvals/${id}/${approve ? "approve" : "reject"}`, { method: "POST", body: JSON.stringify({ reason: approve ? "Reviewed" : "Declined" }) });
      setNotice(approve ? "Approved. The same run will create one ticket." : "Rejected. No ticket will be created.");
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Decision failed");
    }
  }

  return (
    <div>
      <p className="kicker">Reviewer</p>
      <h1 className="text-2xl font-semibold">Approval queue</h1>
      {error && <p className="mt-3 text-rose">{error}</p>}
      {notice && <p className="mt-3 text-moss">{notice}</p>}
      <div className="mt-4 space-y-3">
        {rows.length === 0 && <p className="text-sm text-mist">No pending approvals.</p>}
        {rows.map((row) => (
          <article key={row.id} className="panel p-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h2 className="font-mono text-sm">{row.tool}</h2>
              <span className="text-xs text-amber">{row.policyDecision} · {row.requiredRole} · risk {row.risk}</span>
            </div>
            <p className="mt-2 text-sm">{row.reason}</p>
            <dl className="mt-3 grid gap-2 text-sm md:grid-cols-2">
              <div><dt className="kicker">Requester</dt><dd>{row.requesterEmail}</dd></div>
              <div><dt className="kicker">Policy</dt><dd>{row.policyCode}</dd></div>
              <div><dt className="kicker">Run</dt><dd><Link className="text-sky" href={`/runs/${row.runId}`}>{row.runId}</Link></dd></div>
              <div><dt className="kicker">Trace</dt><dd className="font-mono text-xs">{row.traceId}</dd></div>
            </dl>
            <pre className="mt-3 overflow-auto rounded bg-ink p-3 text-xs">{JSON.stringify(row.arguments, null, 2)}</pre>
            <div className="mt-3 flex gap-2">
              <button className="btn-primary" onClick={() => decide(row.id, true)}>Approve</button>
              <button className="btn-danger" onClick={() => decide(row.id, false)}>Reject</button>
            </div>
          </article>
        ))}
      </div>
    </div>
  );
}
