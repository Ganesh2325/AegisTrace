"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "../../lib/api";
import { Button } from "../../components/ui/Button";
import { FormError, TextField } from "../../components/ui/Field";
import { Card } from "../../components/ui/Card";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("dev.operator@aegistrace.local");
  const [password, setPassword] = useState("change-me-dev-password");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await api("/api/v1/auth/login", { method: "POST", body: JSON.stringify({ email, password }) });
      router.replace("/");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Login failed");
      setBusy(false);
    }
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-6 py-12">
      <p className="text-[11px] font-medium uppercase tracking-[0.14em] text-muted">AegisTrace</p>
      <h1 className="mt-2 text-2xl font-semibold tracking-tight">Sign in</h1>
      <Card variant="warning" className="mt-4">
        <p className="text-sm text-paper">Development only. The password is the value of AEGIS_SEED_PASSWORD. It is not a production credential.</p>
      </Card>
      <form onSubmit={submit} className="mt-5 space-y-4">
        <TextField label="Email" value={email} onChange={(event) => setEmail(event.target.value)} autoComplete="username" />
        <TextField label="Password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" />
        {error && <FormError>{error}</FormError>}
        <Button className="w-full" type="submit" loading={busy} loadingLabel="Signing in…">Continue</Button>
      </form>
    </main>
  );
}
