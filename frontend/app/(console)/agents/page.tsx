"use client";

import { useEffect, useState } from "react";
import { api, roleOf } from "../../../lib/api";
import { canAccess } from "../../../lib/access";
import { readSession } from "../../../lib/session";
import { Button, ButtonLink } from "../../../components/ui/Button";
import { Card } from "../../../components/ui/Card";
import { Dialog } from "../../../components/ui/Overlay";
import { FieldGroup, FormError, TextAreaField, TextField } from "../../../components/ui/Field";
import { Page, PageHeader } from "../../../components/ui/PageHeader";
import { StatusBadge } from "../../../components/ui/StatusBadge";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/States";
import { Mono, Timestamp } from "../../../components/ui/Type";
import { knowledgeLabel, modelLabel, toolCountLabel, versionLabel, type AgentSummary } from "../../../lib/agents";

export default function AgentsPage() {
  const role = roleOf(readSession().current);
  const canConfigure = canAccess(role, "agents.configure");
  const [agents, setAgents] = useState<AgentSummary[]>([]);
  const [error, setError] = useState("");
  const [ready, setReady] = useState(false);
  const [pendingId, setPendingId] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [createError, setCreateError] = useState("");
  const [creating, setCreating] = useState(false);

  function load() {
    api<AgentSummary[]>("/api/v1/agents")
      .then((rows) => { setError(""); setAgents(rows); })
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load agents"))
      .finally(() => setReady(true));
  }
  useEffect(load, []);

  async function setStatus(agent: AgentSummary) {
    if (pendingId || !canConfigure) return;
    const status = agent.status === "ACTIVE" ? "INACTIVE" : "ACTIVE";
    setPendingId(agent.id);
    try {
      await api(`/api/v1/agents/${agent.id}/status`, { method: "POST", body: JSON.stringify({ status }) });
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not change status");
    } finally {
      setPendingId("");
    }
  }

  async function createAgent() {
    if (creating) return;
    if (!name.trim()) {
      setCreateError("A name is required.");
      return;
    }
    setCreating(true);
    setCreateError("");
    try {
      await api("/api/v1/agents", { method: "POST", body: JSON.stringify({ name: name.trim(), description }) });
      setCreateOpen(false);
      setName("");
      setDescription("");
      load();
    } catch (err) {
      setCreateError(err instanceof Error ? err.message : "Could not create agent");
    } finally {
      setCreating(false);
    }
  }

  return (
    <Page width="wide">
      <PageHeader
        eyebrow="Control plane"
        title="Agents"
        description="Each run keeps the agent version it started with. Changing status or activating another version affects future runs only."
        actions={canConfigure ? <Button onClick={() => setCreateOpen(true)}>New agent</Button> : undefined}
      />
      {error && <ErrorState title="Unable to load agents" onRetry={load}>{error}</ErrorState>}
      {!error && !ready && <Skeleton className="h-24" />}
      {!error && ready && agents.length === 0 && (
        <EmptyState title="No agents">This workspace has no agent records yet.</EmptyState>
      )}
      <div className="grid gap-3 md:grid-cols-2">
        {agents.map((agent) => (
          <Card key={agent.id} variant="interactive">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="text-base font-medium text-paper">{agent.name}</h2>
                  <StatusBadge status={agent.status === "ACTIVE" ? "ACTIVE" : "INACTIVE"} />
                </div>
                <p className="mt-1 text-sm text-muted">{agent.description || "No description."}</p>
                <dl className="mt-3 grid grid-cols-2 gap-x-3 gap-y-1 text-xs text-muted sm:grid-cols-3">
                  <div><dt className="uppercase tracking-[0.12em]">Version</dt><dd className="mt-0.5 text-paper"><Mono>{versionLabel(agent.currentVersion)}</Mono></dd></div>
                  <div><dt className="uppercase tracking-[0.12em]">Model</dt><dd className="mt-0.5 text-paper">{modelLabel(agent.provider, agent.model)}</dd></div>
                  <div><dt className="uppercase tracking-[0.12em]">Tools</dt><dd className="mt-0.5 text-paper">{toolCountLabel(agent.toolCount)}</dd></div>
                  <div><dt className="uppercase tracking-[0.12em]">Knowledge</dt><dd className="mt-0.5 text-paper">{knowledgeLabel(agent.knowledgeName)}</dd></div>
                  <div><dt className="uppercase tracking-[0.12em]">Runs</dt><dd className="mt-0.5 text-paper">{agent.runCount}</dd></div>
                  <div><dt className="uppercase tracking-[0.12em]">Updated</dt><dd className="mt-0.5"><Timestamp value={agent.updatedAt} /></dd></div>
                </dl>
              </div>
              <div className="flex shrink-0 flex-col items-end gap-2">
                <ButtonLink href={`/agents/${agent.id}`} variant="secondary">Open</ButtonLink>
                {canConfigure && (
                  <Button variant="ghost" loading={pendingId === agent.id} loadingLabel="Saving…" onClick={() => setStatus(agent)}>
                    {agent.status === "ACTIVE" ? "Deactivate" : "Activate"}
                  </Button>
                )}
              </div>
            </div>
          </Card>
        ))}
      </div>
      <Dialog
        open={createOpen}
        title="New agent"
        description="The agent starts INACTIVE until a version is created and activated."
        confirmLabel="Create agent"
        loading={creating}
        error={createError}
        onClose={() => setCreateOpen(false)}
        onConfirm={createAgent}
      >
        <FieldGroup>
          <TextField label="Name" value={name} onChange={(event) => setName(event.target.value)} />
          <TextAreaField label="Description" value={description} onChange={(event) => setDescription(event.target.value)} />
          {!name.trim() && createError && <FormError>{createError}</FormError>}
        </FieldGroup>
      </Dialog>
    </Page>
  );
}
