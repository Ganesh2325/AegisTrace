"use client";

import { useEffect, useState } from "react";
import { api } from "../../../lib/api";

type Agent = { id: string; name: string; description: string; status: string; currentVersion: number | null; provider: string; model: string };

export default function AgentsPage() {
  const [agents, setAgents] = useState<Agent[]>([]);
  const [error, setError] = useState("");

  function load() {
    api<Agent[]>("/api/v1/agents").then(setAgents).catch((err) => setError(err.message));
  }
  useEffect(load, []);

  async function setStatus(agent: Agent) {
    const status = agent.status === "ACTIVE" ? "INACTIVE" : "ACTIVE";
    await api(`/api/v1/agents/${agent.id}/status`, { method: "POST", body: JSON.stringify({ status }) });
    load();
  }

  return (
    <div>
      <p className="kicker">Developer</p>
      <h1 className="text-2xl font-semibold">Agents</h1>
      <p className="mt-2 max-w-2xl text-sm text-mist">Changing status does not rewrite runs that already stored a version snapshot.</p>
      {error && <p className="mt-3 text-rose">{error}</p>}
      <div className="mt-4 space-y-3">
        {agents.map((agent) => (
          <article key={agent.id} className="panel p-4">
            <div className="flex items-start justify-between gap-3">
              <div>
                <h2 className="text-lg">{agent.name}</h2>
                <p className="text-sm text-mist">{agent.description}</p>
                <p className="mt-2 font-mono text-xs text-mist">v{agent.currentVersion ?? "—"} · {agent.provider} · {agent.model}</p>
              </div>
              <button className="btn-ghost" onClick={() => setStatus(agent)}>{agent.status}</button>
            </div>
          </article>
        ))}
      </div>
    </div>
  );
}
