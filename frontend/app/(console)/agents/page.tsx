"use client";

import { useEffect, useState } from "react";
import { api } from "../../../lib/api";
import { Button } from "../../../components/ui/Button";
import { Card } from "../../../components/ui/Card";
import { Page, PageHeader } from "../../../components/ui/PageHeader";
import { StatusBadge } from "../../../components/ui/StatusBadge";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/States";
import { Mono } from "../../../components/ui/Type";

type Agent = { id: string; name: string; description: string; status: string; currentVersion: number | null; provider: string; model: string };

export default function AgentsPage() {
  const [agents, setAgents] = useState<Agent[]>([]);
  const [error, setError] = useState("");
  const [ready, setReady] = useState(false);
  const [pendingId, setPendingId] = useState("");

  function load() {
    api<Agent[]>("/api/v1/agents").then((rows) => { setError(""); setAgents(rows); }).catch((err) => setError(err.message)).finally(() => setReady(true));
  }
  useEffect(load, []);

  async function setStatus(agent: Agent) {
    if (pendingId) return;
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

  return (
    <Page width="standard">
      <PageHeader eyebrow="Developer" title="Agents" description="Changing status does not rewrite runs that already stored a version snapshot." />
      {error && <ErrorState title="Unable to load agents" onRetry={load}>{error}</ErrorState>}
      {!error && !ready && <Skeleton className="h-24" />}
      {!error && ready && agents.length === 0 && <EmptyState title="No agents">This workspace has no agent records yet.</EmptyState>}
      <div className="space-y-3">
        {agents.map((agent) => (
          <Card key={agent.id}>
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="text-base font-medium">{agent.name}</h2>
                  <StatusBadge status={agent.status === "ACTIVE" ? "ACTIVE" : "INACTIVE"} />
                </div>
                <p className="mt-1 text-sm text-muted">{agent.description}</p>
                <p className="mt-2"><Mono>v{agent.currentVersion ?? "—"} · {agent.provider} · {agent.model}</Mono></p>
              </div>
              <Button variant="ghost" loading={pendingId === agent.id} loadingLabel="Saving…" onClick={() => setStatus(agent)}>{agent.status === "ACTIVE" ? "Deactivate" : "Activate"}</Button>
            </div>
          </Card>
        ))}
      </div>
    </Page>
  );
}
