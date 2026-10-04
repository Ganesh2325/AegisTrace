"use client";

import { useEffect, useState } from "react";
import { api } from "../../../lib/api";

type Event = { id: string; action: string; resourceType: string; resourceId: string; createdAt: string; actorId?: string };

export default function AuditPage() {
  const [items, setItems] = useState<Event[]>([]);
  const [error, setError] = useState("");
  useEffect(() => {
    api<{ items: Event[] }>("/api/v1/audit").then((page) => setItems(page.items)).catch((err) => setError(err.message));
  }, []);
  return (
    <div>
      <p className="kicker">Append only</p>
      <h1 className="text-2xl font-semibold">Audit</h1>
      {error && <p className="mt-3 text-rose">{error}</p>}
      <table className="mt-4 w-full text-left text-sm">
        <thead className="text-mist"><tr><th className="py-2">When</th><th>Action</th><th>Resource</th></tr></thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.id} className="border-t border-line">
              <td className="py-2 font-mono text-xs">{item.createdAt}</td>
              <td>{item.action}</td>
              <td className="font-mono text-xs">{item.resourceType} {item.resourceId}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
