"use client";

import { useEffect, useState } from "react";
import { api } from "../../../lib/api";

const jaeger = process.env.NEXT_PUBLIC_JAEGER_URL || "http://localhost:16686";
const grafana = process.env.NEXT_PUBLIC_GRAFANA_URL || "http://localhost:3001";

export default function ObservabilityPage() {
  const [summary, setSummary] = useState<Record<string, number> | null>(null);
  useEffect(() => { api<Record<string, number>>("/api/v1/metrics/summary").then(setSummary).catch(() => undefined); }, []);
  return (
    <div>
      <p className="kicker">Follow a run id</p>
      <h1 className="text-2xl font-semibold">Observability</h1>
      <p className="mt-2 max-w-2xl text-sm text-mist">The durable timeline is in Postgres. Jaeger shows latency inside one activation. Prompts are not span attributes.</p>
      <div className="mt-4 flex gap-3">
        <a className="btn-ghost" href={jaeger} target="_blank" rel="noreferrer">Jaeger</a>
        <a className="btn-ghost" href={grafana} target="_blank" rel="noreferrer">Grafana</a>
      </div>
      {summary && <pre className="panel mt-4 overflow-auto p-4 text-xs">{JSON.stringify(summary, null, 2)}</pre>}
    </div>
  );
}
