"use client";

import { useEffect, useState } from "react";
import { api } from "../../../lib/api";

type Base = { id: string; name: string; slug: string; embeddingModel: string; status: string };
type Doc = { id: string; title: string; status: string; chunks: number; errorMessage?: string };

export default function KnowledgePage() {
  const [bases, setBases] = useState<Base[]>([]);
  const [docs, setDocs] = useState<Doc[]>([]);
  const [error, setError] = useState("");

  useEffect(() => {
    api<Base[]>("/api/v1/knowledge-bases").then(async (rows) => {
      setBases(rows);
      if (rows[0]) setDocs(await api<Doc[]>(`/api/v1/knowledge-bases/${rows[0].id}/documents`));
    }).catch((err) => setError(err.message));
  }, []);

  return (
    <div>
      <p className="kicker">Corpus</p>
      <h1 className="text-2xl font-semibold">Knowledge</h1>
      <p className="mt-2 max-w-2xl text-sm text-mist">Documents are data. A document that tells the agent to ignore policy does not change approval.</p>
      {error && <p className="mt-3 text-rose">{error}</p>}
      {bases.map((base) => (
        <p key={base.id} className="mt-4 text-sm">{base.name} · {base.embeddingModel} · {base.status}</p>
      ))}
      <table className="mt-4 w-full text-left text-sm">
        <thead className="text-mist"><tr><th className="py-2">Title</th><th>Status</th><th>Chunks</th></tr></thead>
        <tbody>
          {docs.map((doc) => (
            <tr key={doc.id} className="border-t border-line">
              <td className="py-2">{doc.title}</td>
              <td>{doc.status}</td>
              <td>{doc.chunks}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {docs.length === 0 && <p className="mt-4 text-sm text-mist">No documents yet. The worker seeds the synthetic corpus after startup.</p>}
    </div>
  );
}
