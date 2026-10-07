"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, roleOf } from "../../../lib/api";
import { canAccess } from "../../../lib/access";
import type { AgentSummary, AgentVersion } from "../../../lib/agents";
import type { KnowledgeVersion } from "../../../lib/knowledge";
import { type EvaluationOverview, type EvaluationSuite, progressText, rateText } from "../../../lib/evaluations";
import { readSession } from "../../../lib/session";
import { Button, ButtonLink } from "../../../components/ui/Button";
import { Card } from "../../../components/ui/Card";
import { SelectField } from "../../../components/ui/Field";
import { MetricCard } from "../../../components/ui/MetricCard";
import { Page, PageHeader, SectionHeader } from "../../../components/ui/PageHeader";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/States";
import { StatusBadge } from "../../../components/ui/StatusBadge";
import { Mono, Timestamp } from "../../../components/ui/Type";
import { EvaluationNav } from "./EvaluationNav";

export default function EvaluationsPage() {
  const [canManage, setCanManage] = useState(false);
  const [data, setData] = useState<EvaluationOverview | null>(null);
  const [error, setError] = useState("");
  const [starting, setStarting] = useState(false);
  const [showStart, setShowStart] = useState(false);

  const load = useCallback(async () => {
    try {
      setData(await api<EvaluationOverview>("/api/v1/evaluation/overview"));
      setError("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load evaluation");
    }
  }, []);
  useEffect(() => {
    setCanManage(canAccess(roleOf(readSession().current), "evaluation.manage"));
    void load();
  }, [load]);

  return (
    <Page width="wide">
      <PageHeader
        eyebrow="Deterministic evidence"
        title="Evaluation"
        description="Reproducible cases run against explicit agent and knowledge versions. Results use observable responses, citations, policy decisions, approvals, and tool outcomes—never private reasoning."
        actions={canManage && <Button loading={starting} loadingLabel="Starting…" onClick={() => setShowStart((value) => !value)}>Start evaluation</Button>}
      />
      <EvaluationNav />
      {canManage && showStart && <StartEvaluation onStarting={setStarting} />}
      {error && <ErrorState title="Unable to load evaluation" onRetry={() => void load()}>{error}</ErrorState>}
      {!data && !error && <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-28" />)}</div>}
      {data && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <MetricCard label="Executions" value={data.executions} context={`${data.active} active`} hint="Explicit suite executions in this workspace." />
            <MetricCard label="Pass rate" value={rateText(data.passRate)} context={`${data.passRate.numerator} of ${data.passRate.denominator} decisive results`} hint={data.passRate.definition} />
            <MetricCard label="Safety resistance" value={rateText(data.promptInjectionResistance)} context="Prompt-injection cases" hint={data.promptInjectionResistance.definition} />
            <MetricCard label="Regressions" value={data.regressions} context="Latest comparable suite runs" hint="Stored cases that changed from PASS to FAIL." />
          </div>
          <div className="grid gap-3 lg:grid-cols-2">
            <Card>
              <SectionHeader title="Quality checks" description="No denominator is displayed as No data, not zero." />
              <dl className="mt-3 space-y-2 text-sm">
                <RateRow label="Grounding" value={rateText(data.groundingPassRate)} />
                <RateRow label="Citation integrity" value={rateText(data.citationPassRate)} />
                <RateRow label="Policy & tools" value={rateText(data.policyComplianceRate)} />
                <RateRow label="Abstention" value={rateText(data.abstentionCorrectness)} />
              </dl>
            </Card>
            <Card>
              <SectionHeader title="Evaluation semantics" description="Infrastructure failures stay ERROR and do not become model failures." />
              <p className="mt-3 text-sm text-muted">PASS and FAIL form score denominators. ERROR, SKIPPED, INCONCLUSIVE, and CANCELLED remain visible but are excluded from pass-rate math.</p>
              <div className="mt-3"><ButtonLink href="/evaluations/suites" variant="secondary">Browse cases</ButtonLink></div>
            </Card>
          </div>
          <Card>
            <SectionHeader title="Recent executions" description="Progress is completed cases over total cases; no synthetic percentage." action={<ButtonLink href="/evaluations/runs" variant="ghost">All history</ButtonLink>} />
            {data.recent.length === 0 ? (
              <EmptyState title="No evaluation executions">Start a suite against explicit immutable versions.</EmptyState>
            ) : (
              <ul className="mt-3 divide-y divide-line">
                {data.recent.slice(0, 8).map((run) => (
                  <li key={run.id}>
                    <Link href={`/evaluations/runs/${run.id}`} className="flex flex-wrap items-center gap-3 py-3 hover:text-paper">
                      <StatusBadge status={run.status} />
                      <span className="min-w-0 flex-1">{run.suiteName} <span className="text-muted">v{run.suiteVersion}</span></span>
                      <span className="text-xs text-muted">{progressText(run)} cases</span>
                      <Timestamp value={run.createdAt} />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </>
      )}
    </Page>
  );
}

function RateRow({ label, value }: { label: string; value: string }) {
  return <div className="flex justify-between gap-4"><dt className="text-muted">{label}</dt><dd>{value}</dd></div>;
}

function StartEvaluation({ onStarting }: { onStarting: (value: boolean) => void }) {
  const [suites, setSuites] = useState<EvaluationSuite[]>([]);
  const [agents, setAgents] = useState<AgentSummary[]>([]);
  const [versions, setVersions] = useState<AgentVersion[]>([]);
  const [knowledgeVersions, setKnowledgeVersions] = useState<KnowledgeVersion[]>([]);
  const [suiteId, setSuiteId] = useState("");
  const [agentId, setAgentId] = useState("");
  const [agentVersionId, setAgentVersionId] = useState("");
  const [knowledgeVersionId, setKnowledgeVersionId] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const requestKey = useRef("");

  useEffect(() => {
    Promise.all([
      api<EvaluationSuite[]>("/api/v1/evaluation/suites"),
      api<AgentSummary[]>("/api/v1/agents"),
    ]).then(([suiteRows, agentRows]) => {
      setSuites(suiteRows.filter((row) => row.enabled));
      setAgents(agentRows);
      setSuiteId(suiteRows.find((row) => row.enabled)?.id || "");
      setAgentId(agentRows[0]?.id || "");
    }).catch((err) => setError(err.message));
  }, []);

  useEffect(() => {
    if (!agentId) return;
    api<AgentVersion[]>(`/api/v1/agents/${agentId}/versions`).then((rows) => {
      setVersions(rows);
      setAgentVersionId(rows[0]?.id || "");
    }).catch((err) => setError(err.message));
  }, [agentId]);

  useEffect(() => {
    const version = versions.find((row) => row.id === agentVersionId);
    if (!version?.knowledgeBaseId) return;
    api<KnowledgeVersion[]>(`/api/v1/knowledge-bases/${version.knowledgeBaseId}/versions`).then((rows) => {
      setKnowledgeVersions(rows);
      setKnowledgeVersionId(version.knowledgeBaseVersionId || rows[0]?.id || "");
    }).catch((err) => setError(err.message));
  }, [agentVersionId, versions]);

  useEffect(() => {
    requestKey.current = "";
  }, [suiteId, agentVersionId, knowledgeVersionId]);

  async function start() {
    if (!suiteId || !agentVersionId || !knowledgeVersionId) return;
    if (submitting) return;
    setSubmitting(true);
    onStarting(true);
    setError("");
    try {
      if (!requestKey.current) requestKey.current = crypto.randomUUID();
      const row = await api<{ id: string }>("/api/v1/evaluation/runs", {
        method: "POST",
        body: JSON.stringify({
          suiteId,
          agentVersionId,
          knowledgeVersionId,
          requestKey: requestKey.current,
        }),
      });
      window.location.assign(`/evaluations/runs/${row.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to start evaluation");
      onStarting(false);
      setSubmitting(false);
    }
  }

  return (
    <Card className="mb-5">
      <SectionHeader title="Start evaluation" description="The selected immutable versions are recorded on every result." />
      <div className="mt-3 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <SelectField label="Suite" value={suiteId} onChange={(event) => setSuiteId(event.target.value)}>
          {suites.map((suite) => <option key={suite.id} value={suite.id}>{suite.name} v{suite.version}</option>)}
        </SelectField>
        <SelectField label="Agent" value={agentId} onChange={(event) => setAgentId(event.target.value)}>
          {agents.map((agent) => <option key={agent.id} value={agent.id}>{agent.name}</option>)}
        </SelectField>
        <SelectField label="Agent version" value={agentVersionId} onChange={(event) => setAgentVersionId(event.target.value)}>
          {versions.map((version) => <option key={version.id} value={version.id}>v{version.version} · {version.model}</option>)}
        </SelectField>
        <SelectField label="Knowledge version" value={knowledgeVersionId} onChange={(event) => setKnowledgeVersionId(event.target.value)}>
          {knowledgeVersions.map((version) => <option key={version.id} value={version.id}>v{version.version} · {version.documentCount} docs</option>)}
        </SelectField>
      </div>
      {error && <p className="mt-3 text-sm text-danger" role="alert">{error}</p>}
      <div className="mt-3 flex items-center gap-3">
        <Button loading={submitting} loadingLabel="Queueing…" onClick={() => void start()} disabled={!suiteId || !agentVersionId || !knowledgeVersionId}>Queue evaluation</Button>
        <span className="text-xs text-muted"><Mono>{EvaluationFixturesLabel}</Mono></span>
      </div>
    </Card>
  );
}

const EvaluationFixturesLabel = "deterministic-v2";
