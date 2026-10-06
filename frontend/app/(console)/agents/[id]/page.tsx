"use client";

import { FormEvent, useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, ApiError, roleOf } from "../../../../lib/api";
import { canAccess } from "../../../../lib/access";
import { readSession } from "../../../../lib/session";
import { Button } from "../../../../components/ui/Button";
import { Card } from "../../../../components/ui/Card";
import { Dialog } from "../../../../components/ui/Overlay";
import { CheckboxField, FieldGroup, FormError, SelectField, TextAreaField, TextField } from "../../../../components/ui/Field";
import { MetricCard } from "../../../../components/ui/MetricCard";
import { Page, PageHeader, SectionHeader } from "../../../../components/ui/PageHeader";
import { RiskBadge } from "../../../../components/ui/RiskBadge";
import { StatusBadge } from "../../../../components/ui/StatusBadge";
import { EmptyState, ErrorState, ForbiddenState, Skeleton } from "../../../../components/ui/States";
import { DataTable } from "../../../../components/ui/DataTable";
import { Mono, Timestamp } from "../../../../components/ui/Type";
import {
  activationCopy,
  completionLabel,
  formFromVersion,
  formatLimitTimeout,
  knowledgeLabel,
  modelLabel,
  snapshotHasPrompt,
  toolCountLabel,
  validateCreateVersion,
  versionLabel,
  versionStateLabel,
  visibleTabs,
  type AgentAuditEvent,
  type AgentDetail,
  type AgentTab,
  type AgentVersion,
  type CreateVersionInput,
  type KnowledgeOption,
  type RegisteredTool,
} from "../../../../lib/agents";

export default function AgentDetailPage() {
  const params = useParams<{ id: string }>();
  const role = roleOf(readSession().current);
  const canConfigure = canAccess(role, "agents.configure");
  const canReadAudit = canAccess(role, "audit.read");
  const tabs = visibleTabs(canReadAudit);
  const [agent, setAgent] = useState<AgentDetail | null>(null);
  const [versions, setVersions] = useState<AgentVersion[]>([]);
  const [selected, setSelected] = useState<AgentVersion | null>(null);
  const [audit, setAudit] = useState<AgentAuditEvent[]>([]);
  const [tab, setTab] = useState<AgentTab>("overview");
  const [error, setError] = useState("");
  const [forbidden, setForbidden] = useState(false);
  const [ready, setReady] = useState(false);
  const [pending, setPending] = useState("");
  const [activateTarget, setActivateTarget] = useState<AgentVersion | null>(null);
  const [form, setForm] = useState<CreateVersionInput | null>(null);
  const [tools, setTools] = useState<RegisteredTool[]>([]);
  const [knowledge, setKnowledge] = useState<KnowledgeOption[]>([]);
  const [formError, setFormError] = useState("");
  const [dirty, setDirty] = useState(false);

  function load() {
    Promise.all([
      api<AgentDetail>(`/api/v1/agents/${params.id}`),
      api<AgentVersion[]>(`/api/v1/agents/${params.id}/versions`),
    ]).then(([detail, rows]) => {
      setForbidden(false);
      setError("");
      setAgent(detail);
      setVersions(rows);
      if (canReadAudit) {
        api<AgentAuditEvent[]>(`/api/v1/agents/${params.id}/audit`).then(setAudit).catch(() => setAudit([]));
      }
    }).catch((err) => {
      if (err instanceof ApiError && err.status === 403) setForbidden(true);
      else setError(err instanceof Error ? err.message : "Unable to load agent");
    }).finally(() => setReady(true));
  }

  useEffect(load, [params.id, canReadAudit]);

  useEffect(() => {
    if (!form || !dirty) return;
    function warn(event: BeforeUnloadEvent) {
      event.preventDefault();
      event.returnValue = "";
    }
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [form, dirty]);

  useEffect(() => {
    if (!canConfigure || tab !== "configuration") return;
    Promise.all([
      api<RegisteredTool[]>("/api/v1/tools"),
      api<KnowledgeOption[]>("/api/v1/knowledge-bases"),
    ]).then(([toolRows, kbRows]) => {
      setTools(toolRows);
      setKnowledge(kbRows);
    }).catch((err) => setFormError(err instanceof Error ? err.message : "Unable to load configuration options"));
  }, [canConfigure, tab]);

  useEffect(() => {
    if (!agent || form) return;
    setForm(formFromVersion(agent.activeVersion, agent.activeVersion?.environment || ""));
  }, [agent, form]);

  if (forbidden) return <ForbiddenState capability="agents.read" />;
  if (error) return <ErrorState title="Unable to load this agent" onRetry={load}>{error}</ErrorState>;
  if (!ready || !agent) return <Skeleton className="h-40" />;

  const record = agent;
  const active = record.activeVersion;
  const inspect = selected || active;

  async function changeStatus() {
    if (!canConfigure || pending) return;
    const status = record.status === "ACTIVE" ? "INACTIVE" : "ACTIVE";
    setPending("status");
    try {
      await api(`/api/v1/agents/${record.id}/status`, { method: "POST", body: JSON.stringify({ status }) });
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not change status");
    } finally {
      setPending("");
    }
  }

  async function activate() {
    if (!activateTarget || pending) return;
    setPending("activate");
    try {
      await api(`/api/v1/agents/${record.id}/versions/${activateTarget.id}/activate`, { method: "POST" });
      setActivateTarget(null);
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Activation failed");
      setActivateTarget(null);
    } finally {
      setPending("");
    }
  }

  async function openVersion(row: AgentVersion) {
    const full = await api<AgentVersion>(`/api/v1/agents/${record.id}/versions/${row.id}`);
    setSelected(full);
    setTab("versions");
  }

  async function createVersion(event: FormEvent) {
    event.preventDefault();
    if (!form || !canConfigure || pending) return;
    const problems = validateCreateVersion(form);
    if (problems.length > 0) {
      setFormError(problems[0]);
      return;
    }
    setPending("create");
    setFormError("");
    try {
      await api(`/api/v1/agents/${record.id}/versions`, {
        method: "POST",
        body: JSON.stringify({
          provider: form.provider,
          model: form.model,
          temperature: form.temperature,
          maxTokens: form.maxTokens,
          timeoutMs: form.timeoutMs,
          maxToolCalls: form.maxToolCalls,
          costBudgetUsd: form.costBudgetUsd,
          tokenBudget: form.tokenBudget,
          systemPrompt: form.reusePrompt ? undefined : form.systemPrompt,
          promptVersionId: form.reusePrompt ? form.promptVersionId : undefined,
          knowledgeBaseId: form.knowledgeBaseId,
          toolNames: form.toolNames,
          environment: form.environment,
        }),
      });
      setDirty(false);
      setForm(null);
      load();
      setTab("versions");
    } catch (err) {
      setFormError(err instanceof Error ? err.message : "Could not create version");
    } finally {
      setPending("");
    }
  }

  function patchForm(next: Partial<CreateVersionInput>) {
    if (!form) return;
    setDirty(true);
    setForm({ ...form, ...next });
  }

  const copy = activateTarget ? activationCopy(record.name, activateTarget.version) : null;

  return (
    <Page width="wide">
      <PageHeader
        eyebrow="Control plane"
        title={record.name}
        description={record.description || undefined}
        status={<StatusBadge status={record.status === "ACTIVE" ? "ACTIVE" : "INACTIVE"} />}
        actions={
          <>
            {canConfigure && (
              <Button variant="ghost" loading={pending === "status"} loadingLabel="Saving…" onClick={changeStatus}>
                {record.status === "ACTIVE" ? "Deactivate agent" : "Activate agent"}
              </Button>
            )}
            {canConfigure && <Button onClick={() => setTab("configuration")}>Create version</Button>}
          </>
        }
      />
      <p className="text-sm text-muted">
        Current version {versionLabel(active?.version)} · {modelLabel(active?.provider, active?.model)}
      </p>
      <div role="tablist" aria-label="Agent sections" className="flex flex-wrap gap-1 overflow-x-auto border-b border-line pb-px">
        {tabs.map((entry) => (
          <button
            key={entry.id}
            type="button"
            role="tab"
            aria-selected={tab === entry.id}
            className={`whitespace-nowrap rounded-t-md px-3 py-2 text-sm ${tab === entry.id ? "bg-elevated text-paper" : "text-muted hover:text-paper"}`}
            onClick={() => setTab(entry.id)}
          >
            {entry.label}
          </button>
        ))}
      </div>

      {tab === "overview" && (
        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <MetricCard label="Current version" value={versionLabel(active?.version)} context={active ? modelLabel(active.provider, active.model) : undefined} />
            <MetricCard label="Runs" value={record.usage.runCount} context={record.usage.lastRunAt ? "Last run recorded" : "No runs yet"} />
            <MetricCard label="Completion" value={completionLabel(record.usage)} note={record.usage.completionStatus === "NO_DATA" ? "No terminal runs" : undefined} />
            <MetricCard label="Workspace" value={record.workspaceName} context={`Created ${new Date(record.createdAt).toISOString().slice(0, 10)}`} />
          </div>
          {inspect ? <VersionPanel version={inspect} /> : <EmptyState title="No current version">Create and activate a version before starting support runs.</EmptyState>}
        </div>
      )}

      {tab === "versions" && (
        <div className="space-y-4">
          <DataTable
            columns={[
              { key: "v", header: "Version", cell: (row) => <Mono>v{row.version}</Mono> },
              { key: "s", header: "State", cell: (row) => <StatusBadge status={row.current ? "ACTIVE" : "INACTIVE"} label={versionStateLabel(row.current)} /> },
              { key: "m", header: "Model", cell: (row) => row.model },
              { key: "p", header: "Prompt", cell: (row) => <Mono>v{row.promptVersionNumber}</Mono> },
              { key: "t", header: "Tools", cell: (row) => toolCountLabel(row.toolCount) },
              { key: "k", header: "Knowledge", cell: (row) => knowledgeLabel(row.knowledgeName) },
              { key: "c", header: "Created", cell: (row) => <Timestamp value={row.createdAt} /> },
            ]}
            rows={versions}
            getKey={(row) => row.id}
            empty={<EmptyState title="No versions">This agent has no versions yet.</EmptyState>}
            actions={(row) => (
              <div className="flex flex-wrap gap-2">
                <Button variant="ghost" onClick={() => openVersion(row)}>Inspect</Button>
                {canConfigure && !row.current && (
                  <Button variant="secondary" onClick={() => setActivateTarget(row)}>Activate</Button>
                )}
              </div>
            )}
          />
          {inspect && <VersionPanel version={inspect} />}
        </div>
      )}

      {tab === "configuration" && (
        <div className="space-y-4">
          {active ? <VersionPanel version={active} /> : <EmptyState title="No current version">There is no current version to inspect.</EmptyState>}
          {canConfigure && form && (
            <Card>
              <SectionHeader title="Create version" description="The new version stays not current until you activate it. Historical versions are insert-only." />
              <form className="mt-4 space-y-4" onSubmit={createVersion}>
                <div className="grid gap-3 md:grid-cols-2">
                  <TextField label="Provider" value={form.provider} onChange={(event) => patchForm({ provider: event.target.value })} />
                  <TextField label="Model" value={form.model} onChange={(event) => patchForm({ model: event.target.value })} />
                  <TextField label="Temperature" type="number" step="0.1" value={String(form.temperature)} onChange={(event) => patchForm({ temperature: Number(event.target.value) })} />
                  <TextField label="Max tokens" type="number" value={String(form.maxTokens)} onChange={(event) => patchForm({ maxTokens: Number(event.target.value) })} />
                  <TextField label="Timeout (ms)" type="number" value={String(form.timeoutMs)} onChange={(event) => patchForm({ timeoutMs: Number(event.target.value) })} />
                  <TextField label="Max tool calls" type="number" value={String(form.maxToolCalls)} onChange={(event) => patchForm({ maxToolCalls: Number(event.target.value) })} />
                  <TextField label="Token budget" type="number" value={String(form.tokenBudget)} onChange={(event) => patchForm({ tokenBudget: Number(event.target.value) })} />
                  <TextField label="Cost budget (USD)" type="number" step="0.01" value={String(form.costBudgetUsd)} onChange={(event) => patchForm({ costBudgetUsd: Number(event.target.value) })} />
                  <TextField label="Environment" value={form.environment} onChange={(event) => patchForm({ environment: event.target.value })} hint="Uses the workspace environment value already stored on versions." />
                  <SelectField label="Knowledge base" value={form.knowledgeBaseId} onChange={(event) => patchForm({ knowledgeBaseId: event.target.value })}>
                    <option value="">Select a knowledge base</option>
                    {knowledge.map((row) => <option key={row.id} value={row.id}>{row.name}</option>)}
                  </SelectField>
                </div>
                <FieldGroup legend="Tools">
                  {tools.map((tool) => (
                    <CheckboxField
                      key={tool.name}
                      label={`${tool.name} · ${tool.classification} · ${tool.risk}`}
                      checked={form.toolNames.includes(tool.name)}
                      onChange={(event) => {
                        const next = event.target.checked
                          ? [...form.toolNames, tool.name]
                          : form.toolNames.filter((name) => name !== tool.name);
                        patchForm({ toolNames: next });
                      }}
                    />
                  ))}
                </FieldGroup>
                <FieldGroup legend="Prompt">
                  <CheckboxField
                    label="Reuse the current prompt version"
                    checked={form.reusePrompt}
                    onChange={(event) => patchForm({ reusePrompt: event.target.checked })}
                  />
                  {form.reusePrompt && <p className="text-xs text-muted">Prompt {form.promptVersionId ? `v${active?.promptVersionNumber ?? ""}` : "unavailable"}. The system prompt body is not loaded in this console.</p>}
                  {!form.reusePrompt && (
                    <TextAreaField
                      label="New system prompt"
                      value={form.systemPrompt}
                      onChange={(event) => patchForm({ systemPrompt: event.target.value })}
                      hint="This inserts a new prompt version. Historical prompt versions stay unchanged."
                    />
                  )}
                </FieldGroup>
                <Card variant="compact">
                  <SectionHeader title="Review" />
                  <dl className="mt-2 grid gap-1 text-sm text-muted sm:grid-cols-2">
                    <div>Model <span className="text-paper">{form.provider} / {form.model}</span></div>
                    <div>Prompt <span className="text-paper">{form.reusePrompt ? `reuse ${form.promptVersionId.slice(0, 8)}` : "new version"}</span></div>
                    <div>Tools <span className="text-paper">{form.toolNames.join(", ") || "none"}</span></div>
                    <div>Knowledge <span className="text-paper">{knowledge.find((row) => row.id === form.knowledgeBaseId)?.name || "none"}</span></div>
                    <div>Limits <span className="text-paper">{form.maxToolCalls} tool calls · {formatLimitTimeout(form.timeoutMs)}</span></div>
                    <div>Environment <span className="text-paper">{form.environment || "required"}</span></div>
                  </dl>
                </Card>
                {formError && <FormError>{formError}</FormError>}
                <Button type="submit" loading={pending === "create"} loadingLabel="Creating…">Create version</Button>
              </form>
            </Card>
          )}
          {!canConfigure && <p className="text-sm text-muted">Your role can inspect configuration. Creating or activating versions requires a developer or administrator.</p>}
        </div>
      )}

      {tab === "usage" && (
        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-3">
            <MetricCard label="Runs" value={record.usage.runCount} />
            <MetricCard label="Completed" value={record.usage.completedCount} />
            <MetricCard label="Completion" value={completionLabel(record.usage)} />
          </div>
          <DataTable
            columns={[
              { key: "id", header: "Run", cell: (row) => <Link className="text-info hover:underline" href={`/runs/${row.id}`}>{row.id.slice(0, 8)}</Link> },
              { key: "s", header: "State", cell: (row) => <StatusBadge status={row.state} /> },
              { key: "v", header: "Version", cell: (row) => <Mono>v{row.agentVersionNumber}</Mono> },
              { key: "c", header: "Created", cell: (row) => <Timestamp value={row.createdAt} /> },
            ]}
            rows={record.recentRuns}
            getKey={(row) => row.id}
            empty={<EmptyState title="No visible runs">No runs for this agent are visible to your role.</EmptyState>}
          />
        </div>
      )}

      {tab === "audit" && canReadAudit && (
        <DataTable
          columns={[
            { key: "a", header: "Action", cell: (row) => row.action },
            { key: "r", header: "Resource", cell: (row) => <Mono>{row.resourceType}</Mono> },
            { key: "c", header: "When", cell: (row) => <Timestamp value={row.createdAt} /> },
          ]}
          rows={audit}
          getKey={(row) => row.id}
          empty={<EmptyState title="No control-plane events">Mutations on this agent have not produced audit rows yet.</EmptyState>}
        />
      )}

      <Dialog
        open={Boolean(activateTarget && copy)}
        title={copy?.title || ""}
        description={copy?.description}
        confirmLabel="Activate version"
        loading={pending === "activate"}
        onClose={() => setActivateTarget(null)}
        onConfirm={activate}
      />
    </Page>
  );
}

function VersionPanel({ version }: { version: AgentVersion }) {
  const leaked = snapshotHasPrompt(version.snapshot);
  return (
    <Card>
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-sm font-medium text-paper">Version {versionLabel(version.version)}</h2>
        <StatusBadge status={version.current ? "ACTIVE" : "INACTIVE"} label={versionStateLabel(version.current)} />
      </div>
      <dl className="mt-3 grid gap-2 text-sm text-muted sm:grid-cols-2 lg:grid-cols-3">
        <div>Model <div className="text-paper">{modelLabel(version.provider, version.model)}</div></div>
        <div>Prompt <div className="text-paper">v{version.promptVersionNumber}</div></div>
        <div>Tools <div className="text-paper">{toolCountLabel(version.toolCount)}</div></div>
        <div>Knowledge <div className="text-paper">{knowledgeLabel(version.knowledgeName)}</div></div>
        <div>Knowledge version <div className="text-paper">{version.knowledgeVersion || version.knowledgeVersionStatus}</div></div>
        <div>Timeout <div className="text-paper">{formatLimitTimeout(version.timeoutMs)}</div></div>
        <div>Tool calls <div className="text-paper">{version.maxToolCalls}</div></div>
        <div>Token budget <div className="text-paper">{version.tokenBudget ?? "Not persisted in columns"}</div></div>
        <div>Cost budget <div className="text-paper">{version.costBudgetUsd == null ? "Not available" : String(version.costBudgetUsd)}</div></div>
        <div>Environment <div className="text-paper">{version.environment}</div></div>
        <div>Created <div className="text-paper"><Timestamp value={version.createdAt} /></div></div>
        <div>Created by <div className="text-paper">{version.createdByEmail}</div></div>
        <div>Used by runs <div className="text-paper">{version.usedByRunCount}</div></div>
      </dl>
      {version.tools && version.tools.length > 0 && (
        <ul className="mt-3 space-y-2">
          {version.tools.map((tool) => (
            <li key={tool.name} className="flex flex-wrap items-center gap-2 text-sm">
              <span className="text-paper">{tool.name}</span>
              <Mono>{tool.classification}</Mono>
              <RiskBadge level={tool.risk} />
              {tool.approvalRequired ? <StatusBadge status="PENDING" label="Approval required" /> : <StatusBadge status="ACTIVE" label="No approval" />}
              <StatusBadge status={tool.enabled ? "ACTIVE" : "INACTIVE"} label={tool.enabled ? "Enabled" : "Disabled"} />
            </li>
          ))}
        </ul>
      )}
      {leaked && <p className="mt-3 text-sm text-danger">System prompt unexpectedly present in the API snapshot.</p>}
    </Card>
  );
}
