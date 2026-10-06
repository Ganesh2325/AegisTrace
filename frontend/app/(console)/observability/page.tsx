"use client";

import { useEffect, useState } from "react";
import { api } from "../../../lib/api";
import { Page, PageHeader } from "../../../components/ui/PageHeader";
import { Skeleton, UnavailableState } from "../../../components/ui/States";
import { CodeBlock, TextLink } from "../../../components/ui/Type";

const jaeger = process.env.NEXT_PUBLIC_JAEGER_URL || "http://localhost:16686";
const grafana = process.env.NEXT_PUBLIC_GRAFANA_URL || "http://localhost:3001";

export default function ObservabilityPage() {
  const [summary, setSummary] = useState<Record<string, unknown> | null>(null);
  const [error, setError] = useState("");
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let stop = false;
    api<Record<string, unknown>>("/api/v1/metrics/summary")
      .then((row) => { if (!stop) setSummary(row); })
      .catch((err) => { if (!stop) setError(err instanceof Error ? err.message : "The metrics request failed."); });
    return () => { stop = true; };
  }, [attempt]);
  return (
    <Page width="wide">
      <PageHeader eyebrow="Follow a run id" title="Observability" description="The durable timeline is in Postgres. Jaeger shows latency inside one activation. Prompts are not span attributes." />
      <div className="flex gap-4">
        <TextLink href={jaeger} external>Jaeger</TextLink>
        <TextLink href={grafana} external>Grafana</TextLink>
      </div>
      {error && <UnavailableState title="Observability unavailable" onRetry={() => setAttempt((value) => value + 1)}>{`The metrics backend could not be reached. ${error}`}</UnavailableState>}
      {!summary && !error && <Skeleton className="h-40" />}
      {summary && <CodeBlock value={JSON.stringify(summary, null, 2)} />}
    </Page>
  );
}
