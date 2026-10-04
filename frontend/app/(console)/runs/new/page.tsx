"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "../../../../lib/api";

const DEMO = "Why was my application rejected, and what should I do before reapplying?";

export default function NewRun() {
  const router = useRouter();
  const [question, setQuestion] = useState(DEMO);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const run = await api<{ id: string }>("/api/v1/runs", { method: "POST", body: JSON.stringify({ question }) });
      router.push(`/runs/${run.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not start the run");
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="max-w-3xl">
      <p className="kicker">Operator</p>
      <h1 className="text-2xl font-semibold">Ask the support agent</h1>
      <p className="mt-2 text-sm text-mist">The request returns as soon as the run is stored. Retrieval and the model continue in the background.</p>
      <textarea className="field mt-4 min-h-36" value={question} onChange={(e) => setQuestion(e.target.value)} />
      {error && <p className="mt-3 text-sm text-rose" role="alert">{error}</p>}
      <button className="btn-primary mt-4" disabled={busy || !question.trim()}>{busy ? "Starting…" : "Start run"}</button>
    </form>
  );
}
