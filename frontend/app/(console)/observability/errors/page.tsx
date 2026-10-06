"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { api } from "../../../../lib/api";
import { OBS_WINDOWS, parseObsWindow, type RecentError } from "../../../../lib/observability";
import { Button, ButtonLink } from "../../../../components/ui/Button";
import { Card } from "../../../../components/ui/Card";
import { Page, PageHeader, SectionHeader } from "../../../../components/ui/PageHeader";
import { EmptyState, ErrorState, Skeleton } from "../../../../components/ui/States";
import { StatusBadge } from "../../../../components/ui/StatusBadge";
import { Timestamp } from "../../../../components/ui/Type";
import { ObservabilityNav } from "../ObservabilityNav";

type Body = {
  items: RecentError[];
  groups: { code: string; count: number }[];
  page: number;
  size: number;
  total: number;
};

export default function ErrorsPage() {
  return (
    <Suspense fallback={<Page width="wide"><Skeleton className="h-32" /></Page>}>
      <Errors />
    </Suspense>
  );
}

function Errors() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const window = parseObsWindow(params.get("range"));
  const page = Math.max(Number(params.get("page") || "0") || 0, 0);
  const [data, setData] = useState<Body | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(() => {
    api<Body>(`/api/v1/observability/errors?window=${window}&page=${page}`)
      .then((row) => { setData(row); setError(""); })
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load errors"));
  }, [window, page]);

  useEffect(() => { load(); }, [load]);

  return (
    <Page width="wide">
      <PageHeader eyebrow="Observability" title="Errors" description="Grouped by failure category. Messages are codes, not stack traces." />
      <ObservabilityNav />
      <label className="text-xs text-muted">
        Range
        <select className="ml-2 rounded-md border border-line bg-elevated px-2 py-1 text-sm text-paper" value={window} onChange={(event) => router.replace(`${pathname}?range=${event.target.value}&page=0`)}>
          {OBS_WINDOWS.map((item) => <option key={item} value={item}>{item}</option>)}
        </select>
      </label>
      {error && <ErrorState title="Unable to load errors" onRetry={load}>{error}</ErrorState>}
      {!data && !error && <Skeleton className="h-32" />}
      {data && (
        <>
          <Card>
            <SectionHeader title="Categories" />
            {data.groups.length === 0 ? <p className="mt-3 text-sm text-muted">No grouped errors in this window.</p> : (
              <ul className="mt-3 space-y-1 text-sm">
                {data.groups.map((group) => (
                  <li key={group.code} className="flex justify-between"><span>{group.code}</span><span>{group.count}</span></li>
                ))}
              </ul>
            )}
          </Card>
          {data.items.length === 0 ? (
            <EmptyState title="No failed runs">Nothing in FAILED or TIMED_OUT for this window.</EmptyState>
          ) : (
            <ul className="space-y-2">
              {data.items.map((item) => (
                <li key={item.runId} className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line px-3 py-2">
                  <div>
                    <StatusBadge status="ERROR" />
                    <span className="ml-2 text-sm">{item.category || item.code || item.state}</span>
                    <p className="mt-1 text-xs text-muted">{item.agent} · <Timestamp value={item.at} /></p>
                  </div>
                  <div className="flex gap-2">
                    <ButtonLink href={`/runs/${item.runId}`} variant="ghost">Run</ButtonLink>
                    <ButtonLink href={`/observability/traces/${encodeURIComponent(item.traceId)}`} variant="ghost">Trace</ButtonLink>
                  </div>
                </li>
              ))}
            </ul>
          )}
          <div className="flex gap-2">
            <Button variant="ghost" disabled={page <= 0} onClick={() => router.replace(`${pathname}?range=${window}&page=${page - 1}`)}>Previous</Button>
            <Button variant="ghost" disabled={(page + 1) * (data.size || 25) >= data.total} onClick={() => router.replace(`${pathname}?range=${window}&page=${page + 1}`)}>Next</Button>
          </div>
        </>
      )}
    </Page>
  );
}
