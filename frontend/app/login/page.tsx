"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "../../lib/api";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("dev.operator@aegistrace.local");
  const [password, setPassword] = useState("change-me-dev-password");
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError("");
    try {
      await api("/api/v1/auth/login", { method: "POST", body: JSON.stringify({ email, password }) });
      router.replace("/");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Login failed");
    }
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col justify-center p-6">
      <p className="kicker">AegisTrace</p>
      <h1 className="mt-2 text-3xl font-semibold">Sign in</h1>
      <p className="mt-2 text-sm text-mist">The development password is the value of AEGIS_SEED_PASSWORD. It is not a production credential.</p>
      <form onSubmit={submit} className="mt-6 space-y-3">
        <label className="block text-sm">Email
          <input className="field mt-1" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
        </label>
        <label className="block text-sm">Password
          <input className="field mt-1" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
        </label>
        {error && <p className="text-sm text-rose" role="alert">{error}</p>}
        <button className="btn-primary w-full" type="submit">Continue</button>
      </form>
    </main>
  );
}
