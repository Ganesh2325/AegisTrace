"use client";

import { FormEvent, useEffect, useState } from "react";
import { api } from "../../../lib/api";
import { Button } from "../../../components/ui/Button";
import { Card } from "../../../components/ui/Card";
import { FieldGroup, TextField } from "../../../components/ui/Field";
import { Page, PageHeader } from "../../../components/ui/PageHeader";
import { ErrorState, Skeleton } from "../../../components/ui/States";
import { CodeBlock, Mono } from "../../../components/ui/Type";

type User = { id: string; email: string; displayName: string; role: string; status: string };
type Settings = { maxRunsPerMinute: number; approvalTtlSeconds: number; questionRetentionDays: number; contentLogging: boolean };

export default function AdminPage() {
  const [users, setUsers] = useState<User[]>([]);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [error, setError] = useState("");
  const [policy, setPolicy] = useState("");
  const [busy, setBusy] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let stop = false;
    api<User[]>("/api/v1/admin/users").then((rows) => { if (!stop) setUsers(rows); }).catch((err) => { if (!stop) setError(err.message); });
    api<Settings>("/api/v1/admin/settings").then((row) => { if (!stop) setSettings(row); }).catch(() => undefined);
    return () => { stop = true; };
  }, [attempt]);

  async function dryRun(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    const form = new FormData(event.currentTarget);
    try {
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
    } catch (err) {
      setError(err instanceof Error ? err.message : "Policy check failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Page width="standard">
      <PageHeader eyebrow="Admin" title="Workspace" description="Membership and a policy dry run. The dry run does not execute a tool." />
      {error && <ErrorState title="Unable to load administration" onRetry={() => setAttempt((value) => value + 1)}>{error}</ErrorState>}
      {!error && users.length === 0 && !settings && <Skeleton className="h-24" />}
      {users.length > 0 && (
        <ul className="space-y-2 text-sm">
          {users.map((user) => (
            <li key={user.id} className="rounded-md border border-line bg-elevated px-3 py-2">
              <span className="text-paper">{user.displayName}</span>
              <span className="text-muted"> · {user.email} · </span>
              <Mono>{user.role}</Mono>
            </li>
          ))}
        </ul>
      )}
      {settings && <CodeBlock value={JSON.stringify(settings, null, 2)} />}
      <Card>
        <form onSubmit={dryRun}>
          <FieldGroup legend="Policy dry run">
            <p className="text-xs text-muted">This calls the same engine as a live proposal and does not execute a tool.</p>
            <TextField label="Tool" name="tool" defaultValue="delete_database" />
            <TextField label="Priority" name="priority" defaultValue="urgent" />
            <Button type="submit" loading={busy} loadingLabel="Evaluating…">Evaluate</Button>
            {policy && <p><Mono>{policy}</Mono></p>}
          </FieldGroup>
        </form>
      </Card>
    </Page>
  );
}
