"use client";

import { useCallback, useEffect, useState } from "react";
import { api, roleOf } from "../../../../lib/api";
import { canAccess } from "../../../../lib/access";
import type { EvaluationCase, EvaluationSuite } from "../../../../lib/evaluations";
import { readSession } from "../../../../lib/session";
import { Button } from "../../../../components/ui/Button";
import { Card } from "../../../../components/ui/Card";
import { CheckboxField, SelectField, TextAreaField, TextField } from "../../../../components/ui/Field";
import { Page, PageHeader, SectionHeader } from "../../../../components/ui/PageHeader";
import { EmptyState, ErrorState, Skeleton } from "../../../../components/ui/States";
import { StatusBadge } from "../../../../components/ui/StatusBadge";
import { Mono } from "../../../../components/ui/Type";
import { EvaluationNav } from "../EvaluationNav";

type PageResult<T> = { items: T[]; total: number };

const categories = ["CORRECTNESS", "GROUNDING", "CITATION", "POLICY", "TOOL_BEHAVIOR", "ABSTENTION", "SAFETY", "PROMPT_INJECTION"];

export default function EvaluationSuitesPage() {
  const [canManage, setCanManage] = useState(false);
  const [cases, setCases] = useState<EvaluationCase[]>([]);
  const [caseTotal, setCaseTotal] = useState(0);
  const [casePage, setCasePage] = useState(0);
  const [suites, setSuites] = useState<EvaluationSuite[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [showCase, setShowCase] = useState(false);
  const [showSuite, setShowSuite] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [caseResult, suiteRows] = await Promise.all([
        api<PageResult<EvaluationCase>>(`/api/v1/evaluation/cases?page=${casePage}`),
        api<EvaluationSuite[]>("/api/v1/evaluation/suites"),
      ]);
      setCases(caseResult.items);
      setCaseTotal(caseResult.total);
      setSuites(suiteRows);
      setError("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load catalog");
    } finally {
      setLoading(false);
    }
  }, [casePage]);
  useEffect(() => {
    setCanManage(canAccess(roleOf(readSession().current), "evaluation.manage"));
    void load();
  }, [load]);

  return (
    <Page width="wide">
      <PageHeader
        eyebrow="Immutable definitions"
        title="Evaluation suites & cases"
        description="Changing expectations creates a new version. Existing executions retain the exact suite and case versions they used."
        actions={canManage && <div className="flex gap-2"><Button variant="secondary" onClick={() => setShowCase((v) => !v)}>New case</Button><Button onClick={() => setShowSuite((v) => !v)}>New suite</Button></div>}
      />
      <EvaluationNav />
      {canManage && showCase && <CreateCase onCreated={() => { setShowCase(false); void load(); }} />}
      {canManage && showSuite && <CreateSuite cases={cases} onCreated={() => { setShowSuite(false); void load(); }} />}
      {error && <ErrorState title="Unable to load evaluation catalog" onRetry={() => void load()}>{error}</ErrorState>}
      {loading && <Skeleton className="h-40" />}
      {!loading && !error && (
        <div className="grid gap-4 xl:grid-cols-[0.8fr_1.2fr]">
          <Card>
            <SectionHeader title="Suites" description="Bounded collections of immutable case versions." />
            {suites.length === 0 ? <EmptyState title="No suites">Create a suite from one or more enabled cases.</EmptyState> : (
              <ul className="mt-3 space-y-2">
                {suites.map((suite) => (
                  <li key={suite.id} className="rounded-md border border-line p-3">
                    <div className="flex items-center justify-between gap-2"><span>{suite.name} <span className="text-muted">v{suite.version}</span></span><StatusBadge status={suite.enabled ? "ACTIVE" : "DISABLED"} /></div>
                    <p className="mt-1 text-sm text-muted">{suite.description}</p>
                    <p className="mt-2 text-xs text-muted">{suite.caseCount} cases{suite.fixtureSource ? ` · ${suite.fixtureSource}` : ""}</p>
                  </li>
                ))}
              </ul>
            )}
          </Card>
          <Card>
            <SectionHeader title="Cases" description={`${cases.length} visible case versions. Inputs are test scenarios, never chain-of-thought expectations.`} />
            {cases.length === 0 ? <EmptyState title="No cases">Create an externally testable evaluation case.</EmptyState> : (
              <ul className="mt-3 divide-y divide-line">
                {cases.map((item) => (
                  <li key={item.id} className="py-3">
                    <div className="flex flex-wrap items-center gap-2"><StatusBadge status={item.enabled ? "ACTIVE" : "DISABLED"} /><span>{item.name}</span><Mono>{item.category}</Mono><span className="text-xs text-muted">v{item.version}</span></div>
                    <p className="mt-1 text-sm text-muted">{item.description}</p>
                    <details className="mt-2 text-xs text-muted">
                      <summary className="cursor-pointer">Observable input and criteria</summary>
                      <p className="mt-2 whitespace-pre-wrap text-paper">{item.input}</p>
                      <pre className="mt-2 overflow-x-auto rounded bg-canvas p-2">{JSON.stringify(item.expectations, null, 2)}</pre>
                    </details>
                  </li>
                ))}
              </ul>
            )}
            {caseTotal > 25 && (
              <div className="mt-3 flex items-center justify-end gap-2">
                <Button variant="ghost" disabled={casePage === 0} onClick={() => setCasePage((value) => value - 1)}>Previous</Button>
                <span className="text-xs text-muted">Page {casePage + 1}</span>
                <Button variant="ghost" disabled={(casePage + 1) * 25 >= caseTotal} onClick={() => setCasePage((value) => value + 1)}>Next</Button>
              </div>
            )}
          </Card>
        </div>
      )}
    </Page>
  );
}

function CreateCase({ onCreated }: { onCreated: () => void }) {
  const [name, setName] = useState("");
  const [category, setCategory] = useState("CORRECTNESS");
  const [input, setInput] = useState("");
  const [criteria, setCriteria] = useState("{}");
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  async function save() {
    setSaving(true);
    try {
      const expectations = JSON.parse(criteria);
      await api("/api/v1/evaluation/cases", { method: "POST", body: JSON.stringify({ name, description: "", category, input, expectations, enabled: true }) });
      onCreated();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to create case");
    } finally { setSaving(false); }
  }
  return (
    <Card className="mb-4">
      <SectionHeader title="New case version" description="Criteria must be structured and externally observable." />
      <div className="mt-3 grid gap-3 md:grid-cols-2">
        <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} />
        <SelectField label="Category" value={category} onChange={(e) => setCategory(e.target.value)}>{categories.map((value) => <option key={value}>{value}</option>)}</SelectField>
      </div>
      <div className="mt-3 grid gap-3 md:grid-cols-2">
        <TextAreaField label="Input / question" value={input} onChange={(e) => setInput(e.target.value)} />
        <TextAreaField label="Expectations JSON" value={criteria} onChange={(e) => setCriteria(e.target.value)} hint='Example: {"expectedAbstention":true}' />
      </div>
      {error && <p className="mt-2 text-sm text-danger" role="alert">{error}</p>}
      <Button className="mt-3" loading={saving} loadingLabel="Creating…" onClick={() => void save()}>Create immutable case</Button>
    </Card>
  );
}

function CreateSuite({ cases, onCreated }: { cases: EvaluationCase[]; onCreated: () => void }) {
  const [name, setName] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  async function save() {
    setSaving(true);
    try {
      await api("/api/v1/evaluation/suites", { method: "POST", body: JSON.stringify({ name, description: "", caseIds: selected, enabled: true }) });
      onCreated();
    } catch (err) { setError(err instanceof Error ? err.message : "Unable to create suite"); }
    finally { setSaving(false); }
  }
  return (
    <Card className="mb-4">
      <SectionHeader title="New suite version" description="Select up to 50 immutable case versions." />
      <div className="mt-3 max-w-md"><TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} /></div>
      <div className="mt-3 grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
        {cases.filter((item) => item.enabled).map((item) => (
          <CheckboxField key={item.id} label={`${item.name} · v${item.version}`} checked={selected.includes(item.id)} onChange={(event) => setSelected((rows) => event.target.checked ? [...rows, item.id] : rows.filter((id) => id !== item.id))} />
        ))}
      </div>
      {error && <p className="mt-2 text-sm text-danger" role="alert">{error}</p>}
      <Button className="mt-3" loading={saving} loadingLabel="Creating…" disabled={!name || selected.length === 0} onClick={() => void save()}>Create immutable suite</Button>
    </Card>
  );
}
