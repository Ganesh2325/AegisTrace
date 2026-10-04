"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api } from "../../../lib/api";

type Evaluation = { id: string; runId: string; model: string; evaluatorVersion: string; datasetVersion: string; passed: boolean; scores: Record<string, number> };

export default function EvaluationsPage() {
  const [rows, setRows] = useState<Evaluation[]>([]);
  const [error, setError] = useState("");
  useEffect(() => { api<Evaluation[]>("/api/v1/evaluations").then(setRows).catch((err) => setError(err.message)); }, []);
  return (
    <div>
      <p className="kicker">Heuristic evaluator v1</p>
      <h1 className="text-2xl font-semibold">Evaluation</h1>
      <p className="mt-2 max-w-2xl text-sm text-mist">Scores check that citations are substrings of retrieved chunks, that a ticket has an approval, and that a run did not create two tickets. They are not a human labeling study.</p>
      {error && <p className="mt-3 text-rose">{error}</p>}
      <div className="mt-4 space-y-3">
        {rows.length === 0 && <p className="text-sm text-mist">No evaluations yet.</p>}
        {rows.map((row) => (
          <article key={row.id} className="panel p-4 text-sm">
            <div className="flex justify-between"><Link className="text-sky" href={`/runs/${row.runId}`}>{row.runId}</Link><span className={row.passed ? "text-moss" : "text-rose"}>{row.passed ? "passed" : "failed"}</span></div>
            <p className="mt-1 text-mist">{row.model} · {row.datasetVersion} · {row.evaluatorVersion}</p>
            <pre className="mt-2 overflow-auto text-xs">{JSON.stringify(row.scores, null, 2)}</pre>
          </article>
        ))}
      </div>
    </div>
  );
}
