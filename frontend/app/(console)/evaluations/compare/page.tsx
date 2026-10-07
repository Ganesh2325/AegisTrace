"use client";

import { useEffect, useState } from "react";
import { api } from "../../../../lib/api";
import type { EvaluationRun } from "../../../../lib/evaluations";
import { Button } from "../../../../components/ui/Button";
import { Card } from "../../../../components/ui/Card";
import { SelectField } from "../../../../components/ui/Field";
import { Page, PageHeader, SectionHeader } from "../../../../components/ui/PageHeader";
import { EmptyState, ErrorState } from "../../../../components/ui/States";
import { StatusBadge } from "../../../../components/ui/StatusBadge";
import { EvaluationNav } from "../EvaluationNav";

type RunPage = { items: EvaluationRun[] };
type Comparison = {
  compatible: boolean; compatibilityReason: string; regressions: number; improvements: number;
  baseline: { id: string; suiteName: string; suiteVersion: number; agentVersion: number; knowledgeVersion: number; evaluatorVersion: string; passRate: number | null };
  candidate: { id: string; suiteName: string; suiteVersion: number; agentVersion: number; knowledgeVersion: number; evaluatorVersion: string; passRate: number | null };
  cases: { caseKey: string; name: string; category: string; baseline: string; candidate: string; change: string }[];
};

export default function EvaluationComparePage() {
  const [runs, setRuns] = useState<EvaluationRun[]>([]);
  const [baseline, setBaseline] = useState("");
  const [candidate, setCandidate] = useState("");
  const [data, setData] = useState<Comparison | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    api<RunPage>("/api/v1/evaluation/runs").then((page) => {
      const completed = page.items.filter((row) => !["QUEUED", "RUNNING"].includes(row.status));
      setRuns(completed);
      setCandidate(completed[0]?.id || "");
      setBaseline(completed[1]?.id || "");
    }).catch((err) => setError(err.message));
  }, []);
  async function compare() {
    try {
      setData(await api<Comparison>(`/api/v1/evaluation/compare?baseline=${baseline}&candidate=${candidate}`));
      setError("");
    } catch (err) { setError(err instanceof Error ? err.message : "Unable to compare evaluations"); }
  }
  return (
    <Page width="wide">
      <PageHeader eyebrow="Deterministic change detection" title="Compare evaluations" description="Regressions are stable case keys that changed from PASS to FAIL. Different suite keys are marked incompatible and are not compared." />
      <EvaluationNav />
      <Card>
        <SectionHeader title="Select executions" description="The candidate is compared with the baseline; neither record is mutated." />
        <div className="mt-3 grid gap-3 md:grid-cols-2">
          <SelectField label="Baseline" value={baseline} onChange={(e) => setBaseline(e.target.value)}><option value="">Select</option>{runs.map((run) => <option key={run.id} value={run.id}>{run.suiteName} · Agent v{run.agentVersion} · {run.createdAt}</option>)}</SelectField>
          <SelectField label="Candidate" value={candidate} onChange={(e) => setCandidate(e.target.value)}><option value="">Select</option>{runs.map((run) => <option key={run.id} value={run.id}>{run.suiteName} · Agent v{run.agentVersion} · {run.createdAt}</option>)}</SelectField>
        </div>
        <Button className="mt-3" disabled={!baseline || !candidate || baseline === candidate} onClick={() => void compare()}>Compare</Button>
      </Card>
      {error && <ErrorState title="Unable to compare evaluations">{error}</ErrorState>}
      {data && (
        <Card>
          <div className="flex flex-wrap items-center gap-3">
            <StatusBadge status={data.compatible ? "COMPLETED" : "INCONCLUSIVE"} label={data.compatible ? "Compatible" : "Incompatible"} />
            <span className="text-sm text-muted">{data.compatibilityReason}</span>
          </div>
          <div className="mt-3 grid gap-3 md:grid-cols-2">
            <Target label="Baseline" value={data.baseline} />
            <Target label="Candidate" value={data.candidate} />
          </div>
          {data.compatible && data.cases.length === 0 && <EmptyState title="No shared cases">The executions do not share stable case keys.</EmptyState>}
          {data.compatible && data.cases.length > 0 && (
            <>
              <p className="mt-3 text-sm">{data.regressions} regressions · {data.improvements} improvements</p>
              <div className="mt-3 overflow-x-auto">
                <table className="w-full min-w-[42rem] text-left text-sm">
                  <thead className="text-xs uppercase text-muted"><tr><th className="py-2">Case</th><th>Category</th><th>Baseline</th><th>Candidate</th><th>Change</th></tr></thead>
                  <tbody>{data.cases.map((row) => <tr key={row.caseKey} className="border-t border-line"><td className="py-2">{row.name}</td><td>{row.category}</td><td><StatusBadge status={row.baseline} /></td><td><StatusBadge status={row.candidate} /></td><td><StatusBadge status={row.change === "REGRESSION" ? "FAILED" : row.change === "IMPROVEMENT" ? "COMPLETED" : "NO_DATA"} label={row.change.toLowerCase()} /></td></tr>)}</tbody>
                </table>
              </div>
            </>
          )}
        </Card>
      )}
    </Page>
  );
}

function Target({ label, value }: { label: string; value: Comparison["baseline"] }) {
  return (
    <div className="rounded-md border border-line p-3 text-sm">
      <h3 className="font-medium">{label}</h3>
      <p className="mt-1 text-muted">{value.suiteName} v{value.suiteVersion}</p>
      <p className="text-muted">Agent v{value.agentVersion} · Knowledge v{value.knowledgeVersion}</p>
      <p className="text-muted">Evaluator {value.evaluatorVersion} · Pass rate {value.passRate == null ? "No data" : `${Math.round(value.passRate * 1000) / 10}%`}</p>
    </div>
  );
}
