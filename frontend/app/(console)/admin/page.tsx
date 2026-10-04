"use client";

import { FormEvent, useEffect, useState } from "react";
import { api } from "../../../lib/api";

type User = { id: string; email: string; displayName: string; role: string; status: string };
type Settings = { maxRunsPerMinute: number; approvalTtlSeconds: number; questionRetentionDays: number; contentLogging: boolean };

export default function AdminPage() {
  const [users, setUsers] = useState<User[]>([]);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [error, setError] = useState("");
  const [policy, setPolicy] = useState("");

  function load() {
    api<User[]>("/api/v1/admin/users").then(setUsers).catch((err) => setError(err.message));
    api<Settings>("/api/v1/admin/settings").then(setSettings).catch(() => undefined);
  }
  useEffect(load, []);

  async function dryRun(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const result = await api<Record<string, string>>("/api/v1/admin/policy-check", {
      method: "POST",
      body: JSON.stringify({
        tool: form.get("tool"),
        arguments: { subject: "x", description: "y", priority: form.get("priority"), category: "general" },
        assigned: true,
        userHasPermission: true,
        priorToolCalls: 1,
        maxToolCalls: 3,
        budgetExceeded: false,
      }),
    });
    setPolicy(`${result.decision} ${result.code}`);
  }

  return (
    <div className="max-w-3xl">
      <p className="kicker">Admin</p>
      <h1 className="text-2xl font-semibold">Workspace</h1>
      {error && <p className="mt-3 text-rose">{error}</p>}
      <ul className="mt-4 space-y-2 text-sm">
        {users.map((user) => <li key={user.id} className="panel px-3 py-2">{user.displayName} · {user.email} · {user.role}</li>)}
      </ul>
      {settings && <pre className="panel mt-4 p-3 text-xs">{JSON.stringify(settings, null, 2)}</pre>}
      <form onSubmit={dryRun} className="panel mt-4 space-y-3 p-4">
        <h2 className="text-sm font-medium">Policy dry run</h2>
        <p className="text-xs text-mist">This calls the same engine as a live proposal and does not execute a tool.</p>
        <input className="field" name="tool" defaultValue="delete_database" />
        <input className="field" name="priority" defaultValue="urgent" />
        <button className="btn-primary" type="submit">Evaluate</button>
        {policy && <p className="font-mono text-sm">{policy}</p>}
      </form>
    </div>
  );
}
