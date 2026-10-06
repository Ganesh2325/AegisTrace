"use client";

import { useEffect, useState } from "react";
import { api } from "../../../lib/api";
import { DataTable } from "../../../components/ui/DataTable";
import { Page, PageHeader } from "../../../components/ui/PageHeader";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/States";
import { Mono, Timestamp } from "../../../components/ui/Type";

type Event = { id: string; action: string; resourceType: string; resourceId: string; createdAt: string; actorId?: string };

export default function AuditPage() {
  const [items, setItems] = useState<Event[]>([]);
  const [error, setError] = useState("");
  const [ready, setReady] = useState(false);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let stop = false;
    api<{ items: Event[] }>("/api/v1/audit")
      .then((page) => { if (!stop) setItems(page.items); })
      .catch((err) => { if (!stop) setError(err.message); })
      .finally(() => { if (!stop) setReady(true); });
    return () => { stop = true; };
  }, [attempt]);
  return (
    <Page width="wide">
      <PageHeader eyebrow="Append only" title="Audit" description="Records are written by the control plane. This page does not edit them." />
      {error && <ErrorState title="Unable to load audit" onRetry={() => setAttempt((value) => value + 1)}>{error}</ErrorState>}
      {!error && !ready && <Skeleton className="h-24" />}
      {!error && ready && (
        <DataTable
          rows={items}
          getKey={(item) => item.id}
          empty={<EmptyState title="No audit events yet">Actions in this workspace will appear here after they are recorded.</EmptyState>}
          columns={[
            { key: "when", header: "When", cell: (item) => <Timestamp value={item.createdAt} /> },
            { key: "action", header: "Action", cell: (item) => item.action },
            { key: "resource", header: "Resource", cell: (item) => <Mono>{item.resourceType} {item.resourceId}</Mono> },
          ]}
        />
      )}
    </Page>
  );
}
