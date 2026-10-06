"use client";

import { FormEvent, KeyboardEvent, useCallback, useEffect, useRef, useState, Suspense } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { ApiError, api, apiBase } from "../../../../lib/api";
import { environmentLabel } from "../../../../lib/access";
import { readSession } from "../../../../lib/session";
import { formatDuration } from "../../../../lib/duration";
import {
  DEMO_QUESTION,
  DRAFT_KEY,
  QUESTION_MAX,
  SSE_EVENT_NAMES,
  activeAgents,
  compactRunId,
  eventLabel,
  eventSummary,
  formatCost,
  formatTokens,
  isTerminal,
  mergeEvents,
  preferAuthoritativeState,
  toolDisplayName,
  type AgentOption,
  type ExecutionPayload,
  type RunEvent,
} from "../../../../lib/supportRun";
import { Button, ButtonLink } from "../../../../components/ui/Button";
import { Card } from "../../../../components/ui/Card";
import { FormError, SelectField, TextAreaField } from "../../../../components/ui/Field";
import { Page, PageHeader, SectionHeader } from "../../../../components/ui/PageHeader";
import { RiskBadge } from "../../../../components/ui/RiskBadge";
import { StatusBadge } from "../../../../components/ui/StatusBadge";
import { EmptyState, ErrorState, ForbiddenState, Skeleton, UnavailableState } from "../../../../components/ui/States";
import { DurationText, Mono, Timestamp } from "../../../../components/ui/Type";
import { useToast } from "../../../../components/ui/Toast";

export default function NewRunPage() {
  return (
    <Suspense fallback={<Page width="wide"><Skeleton className="h-40" /></Page>}>
      <SupportRunWorkspace />
    </Suspense>
  );
}

function SupportRunWorkspace() {
  const router = useRouter();
  const params = useSearchParams();
  const runId = params.get("run");
  const toast = useToast();
  const session = readSession().current;
  const environment = environmentLabel(session?.environment);
  const [question, setQuestion] = useState(DEMO_QUESTION);
  const [agents, setAgents] = useState<AgentOption[]>([]);
  const [agentId, setAgentId] = useState("");
  const [agentsError, setAgentsError] = useState("");
  const [fieldError, setFieldError] = useState("");
  const [createError, setCreateError] = useState("");
  const [busy, setBusy] = useState(false);
  const [payload, setPayload] = useState<ExecutionPayload | null>(null);
  const [loadError, setLoadError] = useState<{ status?: number; message: string } | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState("");
  const [live, setLive] = useState(false);
  const [staleSeconds, setStaleSeconds] = useState<number | null>(null);
  const inflight = useRef(false);
  const dirty = useRef(false);
  const lastGood = useRef(0);

  useEffect(() => {
    const stored = window.sessionStorage.getItem(DRAFT_KEY);
    if (stored) setQuestion(stored);
  }, []);

  useEffect(() => {
    window.sessionStorage.setItem(DRAFT_KEY, question);
  }, [question]);

  useEffect(() => {
    let stop = false;
    api<AgentOption[]>("/api/v1/agents")
      .then((rows) => {
        if (stop) return;
        const active = activeAgents(rows);
        setAgents(active);
        if (active.length === 1) setAgentId(active[0].id);
      })
      .catch((err) => { if (!stop) setAgentsError(err instanceof Error ? err.message : "Unable to load agents"); });
    return () => { stop = true; };
  }, []);

  const load = useCallback(async (id: string) => {
    if (inflight.current) {
      dirty.current = true;
      return;
    }
    inflight.current = true;
    try {
      const row = await api<ExecutionPayload>(`/api/v1/runs/${id}/execution`);
      setPayload((current) => {
        const nextState = preferAuthoritativeState(current?.run.state, row.run.state);
        return {
          ...row,
          run: { ...row.run, state: nextState },
          events: mergeEvents(current?.events || [], row.events || []),
        };
      });
      setLoadError(null);
      lastGood.current = Date.now();
      setStaleSeconds(null);
    } catch (err) {
      const status = err instanceof ApiError ? err.status : undefined;
      const message = err instanceof Error ? err.message : "Unable to load this run";
      setLoadError({ status, message });
      if (status === 401 || status === 403) setPayload(null);
    } finally {
      inflight.current = false;
      if (dirty.current) {
        dirty.current = false;
        void load(id);
      }
    }
  }, []);

  useEffect(() => {
    if (!runId) {
      setPayload(null);
      setLoadError(null);
      setLive(false);
      return;
    }
    void load(runId);
  }, [runId, load]);

  const terminal = isTerminal(payload?.run.state);
  const terminalRef = useRef(false);
  terminalRef.current = terminal;

  useEffect(() => {
    if (!runId) {
      setLive(false);
      return;
    }
    let stop = false;
    const source = new EventSource(`${apiBase}/api/v1/runs/${runId}/events`, { withCredentials: true });
    source.onopen = () => { if (!stop && !terminalRef.current) setLive(true); };
    const refresh = () => { if (!stop && !terminalRef.current) void load(runId); };
    source.onmessage = refresh;
    SSE_EVENT_NAMES.forEach((name) => source.addEventListener(name, refresh));
    source.onerror = () => { if (!stop) setLive(false); };
    const poll = window.setInterval(() => {
      if (stop || terminalRef.current) return;
      if (source.readyState !== EventSource.OPEN) {
        setLive(false);
        setStaleSeconds(Math.max(0, Math.round((Date.now() - lastGood.current) / 1000)));
      }
      void load(runId);
    }, 3000);
    return () => {
      stop = true;
      source.close();
      window.clearInterval(poll);
    };
  }, [runId, load]);

  useEffect(() => {
    if (terminal) setLive(false);
  }, [terminal]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    const trimmed = question.trim();
    if (!trimmed) {
      setFieldError("Enter a support question.");
      return;
    }
    if (trimmed.length > QUESTION_MAX) {
      setFieldError(`Keep the question to ${QUESTION_MAX} characters.`);
      return;
    }
    setBusy(true);
    setCreateError("");
    setFieldError("");
    try {
      const body: { question: string; agentId?: string } = { question: trimmed };
      if (agents.length > 1 && agentId) body.agentId = agentId;
      const created = await api<ExecutionPayload>("/api/v1/runs", { method: "POST", body: JSON.stringify(body) });
      if (!created.run?.id) throw new Error("The run was stored without an id.");
      setPayload(created);
      toast("success", "Run created");
      router.replace(`/runs/new?run=${created.run.id}`);
    } catch (err) {
      setCreateError(err instanceof Error ? err.message : "Could not start the run");
    } finally {
      setBusy(false);
    }
  }

  function onQuestionKey(event: KeyboardEvent<HTMLTextAreaElement>) {
    if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
      event.preventDefault();
      event.currentTarget.form?.requestSubmit();
    }
  }

  async function cancelRun() {
    if (!payload || cancelling || !payload.capabilities.canCancel) return;
    setCancelling(true);
    setCancelError("");
    toast("info", "Cancel requested…");
    try {
      const next = await api<ExecutionPayload>(`/api/v1/runs/${payload.run.id}/cancel`, { method: "POST" });
      const execution = "run" in next && next.run
        ? next
        : await api<ExecutionPayload>(`/api/v1/runs/${payload.run.id}/execution`);
      setPayload(execution);
      toast("success", "Cancellation accepted");
    } catch (err) {
      const message = err instanceof Error ? err.message : "Unable to cancel";
      setCancelError(message);
      toast("error", message);
      if (payload?.run.id) void load(payload.run.id);
    } finally {
      setCancelling(false);
    }
  }

  async function copyId(id: string) {
    try {
      await navigator.clipboard.writeText(id);
      toast("success", "Run ID copied");
    } catch {
      toast("error", "Could not copy the run ID");
    }
  }

  if (loadError?.status === 403) {
    return <ForbiddenState capability="runs.read" />;
  }
  if (loadError?.status === 401) {
    return <ErrorState title="Session expired">Sign in again to continue this run. The backend remains the source of truth.</ErrorState>;
  }

  const run = payload?.run;
  const canCancel = !!payload?.capabilities.canCancel;

  return (
    <Page width="wide">
      <PageHeader
        eyebrow="Support run"
        title="Ask the support agent"
        description="Ask a policy question and inspect the agent's evidence, actions, and outcome."
        status={run ? <StatusBadge status={run.state} /> : undefined}
        actions={canCancel ? (
          <Button variant="ghost" loading={cancelling} loadingLabel="Cancel requested…" onClick={() => void cancelRun()}>
            Cancel run
          </Button>
        ) : undefined}
      />

      {!runId && (
        <form onSubmit={submit} className="grid gap-4 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,0.8fr)]">
          <Card>
            <TextAreaField
              name="question"
              label="Question"
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              onKeyDown={onQuestionKey}
              disabled={busy}
              maxLength={QUESTION_MAX}
              placeholder={DEMO_QUESTION}
              hint={`${question.trim().length} / ${QUESTION_MAX}. Ctrl or Cmd + Enter starts the run.`}
              error={fieldError}
            />
            {createError && <FormError>{createError}</FormError>}
            <div className="mt-4">
              <Button type="submit" loading={busy} loadingLabel="Creating run…" disabled={!question.trim()}>Start run</Button>
            </div>
          </Card>
          <AgentContext agents={agents} agentId={agentId} onAgentId={setAgentId} environment={environment} error={agentsError} />
        </form>
      )}

      {runId && loadError && !run && loadError.status !== 403 && loadError.status !== 401 && (
        <ErrorState title="Unable to load this run" onRetry={() => void load(runId)}>{loadError.message}</ErrorState>
      )}

      {runId && !run && !loadError && (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,0.8fr)]" aria-busy="true" aria-live="polite">
          <Skeleton className="h-48" />
          <div className="space-y-3">
            <p className="text-sm text-muted">Creating run…</p>
            <Skeleton className="h-40" />
          </div>
        </div>
      )}

      {run && payload && (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,0.8fr)]">
          <div className="order-2 space-y-4 lg:order-1">
            <Card>
              <SectionHeader title="Question" />
              <p className="mt-2 whitespace-pre-wrap text-sm leading-6 text-paper">{run.question}</p>
            </Card>
            <AnswerCard run={run} events={payload.events} />
            <EvidenceList run={run} />
            {payload.proposal && <ToolProposalCard proposal={payload.proposal} includeArguments={payload.capabilities.includeProposalArguments} />}
            {run.state === "APPROVAL_REQUIRED" && (
              <Card variant="warning">
                <h2 className="text-sm font-medium text-paper">Waiting for reviewer</h2>
                <p className="mt-1 text-sm text-muted">
                  The agent proposed a support-ticket action. A reviewer must approve it before execution.
                </p>
                <div className="mt-2"><StatusBadge status="APPROVAL_REQUIRED" /></div>
                {payload.capabilities.canReadApprovals && (
                  <div className="mt-3"><ButtonLink href="/approvals" variant="secondary">Open approvals</ButtonLink></div>
                )}
              </Card>
            )}
            <OutcomeCard run={run} />
            {cancelError && <FormError>{cancelError}</FormError>}
            <div className="flex flex-wrap gap-2">
              <ButtonLink href={`/runs/${run.id}`} variant="secondary">View run details</ButtonLink>
              {isTerminal(run.state) && <ButtonLink href="/runs/new" variant="ghost">Start another run</ButtonLink>}
            </div>
          </div>
          <div className="order-1 space-y-4 lg:order-2">
            <ExecutionSummary
              run={run}
              live={live}
              staleSeconds={staleSeconds}
              onRetry={() => void load(run.id)}
              onCopy={() => void copyId(run.id)}
              environment={environment}
            />
            <RunTimeline events={payload.events} loading={!isTerminal(run.state) && payload.events.length === 0} />
          </div>
        </div>
      )}
    </Page>
  );
}

function AgentContext({
  agents,
  agentId,
  onAgentId,
  environment,
  error,
}: {
  agents: AgentOption[];
  agentId: string;
  onAgentId: (id: string) => void;
  environment: string;
  error: string;
}) {
  const selected = agents.find((agent) => agent.id === agentId) || agents[0];
  return (
    <Card>
      <SectionHeader title="Agent" description="The version stored on the run is the one that will execute." />
      {error && <p className="mt-2 text-sm text-danger">{error}</p>}
      {!error && agents.length === 0 && <p className="mt-2 text-sm text-muted">No active agent is available.</p>}
      {agents.length > 1 && (
        <div className="mt-3">
          <SelectField label="Agent" value={agentId} onChange={(event) => onAgentId(event.target.value)}>
            {agents.map((agent) => (
              <option key={agent.id} value={agent.id}>{agent.name} · v{agent.currentVersion}</option>
            ))}
          </SelectField>
        </div>
      )}
      {selected && (
        <dl className="mt-3 space-y-2 text-sm">
          <Meta label="Agent" value={selected.name} />
          {selected.currentVersion != null && <Meta label="Version" value={`v${selected.currentVersion}`} mono />}
          <Meta label="Environment" value={environment} />
          {selected.model ? <Meta label="Model" value={selected.model} mono /> : <Meta label="Model" value="Model not reported" />}
          {selected.provider && <Meta label="Provider" value={selected.provider} />}
        </dl>
      )}
    </Card>
  );
}

function ExecutionSummary({
  run,
  live,
  staleSeconds,
  onRetry,
  onCopy,
  environment,
}: {
  run: ExecutionPayload["run"];
  live: boolean;
  staleSeconds: number | null;
  onRetry: () => void;
  onCopy: () => void;
  environment: string;
}) {
  const cost = formatCost(run.cost);
  const tokens = formatTokens(run.cost);
  const duration = run.durationMs == null ? null : formatDuration(run.durationMs);
  return (
    <Card>
      <SectionHeader title="Execution" />
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <StatusBadge status={run.state} />
        {duration && <DurationText ms={run.durationMs} className="text-sm text-muted" />}
      </div>
      <dl className="mt-3 space-y-2">
        <div className="flex flex-wrap items-center gap-2">
          <dt className="text-xs text-muted">Run</dt>
          <dd><Mono>{compactRunId(run.id)}</Mono></dd>
          <Button variant="ghost" className="h-7 px-2 text-xs" onClick={onCopy}>Copy run ID</Button>
        </div>
        {run.traceId && <Meta label="Trace" value={run.traceId} mono />}
        {run.agentName && <Meta label="Agent" value={run.agentName} />}
        {run.agentVersionNumber != null && <Meta label="Version used" value={`v${run.agentVersionNumber}`} />}
        {run.agentVersionId && <Meta label="Version ID" value={run.agentVersionId} mono />}
        <Meta label="Environment" value={environment} />
        {run.model ? <Meta label="Model" value={run.model} mono /> : <Meta label="Model" value="Model not reported" />}
        {tokens && <Meta label="Tokens" value={tokens} />}
        {cost && <Meta label="Cost" value={cost} />}
      </dl>
      {!isTerminal(run.state) && live && <p className="mt-3 text-xs text-muted">Updates arrive from the run event stream.</p>}
      {!isTerminal(run.state) && !live && (
        <div className="mt-3">
          <UnavailableState title="Live updates unavailable" onRetry={onRetry}>
            {staleSeconds != null && staleSeconds >= 0 ? `Updated ${staleSeconds}s ago. Retry reconnects the event stream.` : "The event stream is not connected. Retry reconnects it."}
          </UnavailableState>
        </div>
      )}
    </Card>
  );
}

function RunTimeline({ events, loading }: { events: RunEvent[]; loading: boolean }) {
  return (
    <Card>
      <SectionHeader title="Timeline" description="Observable system events. Private model reasoning is not shown." />
      {loading && <div className="mt-3 space-y-2" aria-busy="true"><Skeleton className="h-10" /><Skeleton className="h-10" /></div>}
      {!loading && events.length === 0 && <p className="mt-3 text-sm text-muted">No events yet.</p>}
      {events.length > 0 && (
        <ol className="mt-3 space-y-2">
          {events.map((event) => (
            <li key={event.sequence}>
              <Card variant="compact">
                <div className="flex justify-between gap-3 text-sm">
                  <span>{eventLabel(event.eventType)}</span>
                  <StatusBadge status={event.state} />
                </div>
                <div className="mt-1 text-xs text-muted">{eventSummary(event) || <Timestamp value={event.createdAt} />}</div>
              </Card>
            </li>
          ))}
        </ol>
      )}
    </Card>
  );
}

function AnswerCard({ run, events }: { run: ExecutionPayload["run"]; events: RunEvent[] }) {
  const finalAnswer = run.finalResponse?.trim();
  const draft = run.draftAnswer?.trim();
  const abstained = events.some((event) => event.payload?.abstained === true);
  const untrusted = `${finalAnswer || draft || ""}`.includes("Untrusted instructions");
  if (run.state === "FAILED") {
    return (
      <Card variant="danger">
        <SectionHeader title="Run failed" />
        <p className="mt-2 text-sm">{failureCopy(run)}</p>
      </Card>
    );
  }
  if (run.state === "CANCELLED") {
    return (
      <Card>
        <SectionHeader title="Cancelled" />
        <p className="mt-2 text-sm text-muted">{run.errorMessage || "The run was cancelled."}</p>
      </Card>
    );
  }
  if (run.state === "TIMED_OUT") {
    return (
      <Card variant="warning">
        <SectionHeader title="Run timed out" />
        <p className="mt-2 text-sm text-muted">Last known state before timeout is shown in the timeline. {run.durationMs != null ? `Duration ${formatDuration(run.durationMs)}.` : ""}</p>
        {run.errorMessage && <p className="mt-1 text-sm">{run.errorMessage}</p>}
      </Card>
    );
  }
  if (!draft && !finalAnswer) {
    return (
      <EmptyState title="Answer unavailable yet">The agent has not produced a grounded answer for this run.</EmptyState>
    );
  }
  return (
    <Card variant={finalAnswer ? "success" : "default"}>
      <SectionHeader title={finalAnswer ? "Final answer" : "Agent response"} />
      {abstained && !finalAnswer && <p className="mt-2 text-xs text-warning">Insufficient evidence. The text below is the backend abstention.</p>}
      <p className="mt-2 whitespace-pre-wrap text-sm leading-6">{finalAnswer || draft}</p>
      {untrusted && (
        <p className="mt-3 text-xs text-warning">Retrieved text included instructions that were ignored. Permissions did not change.</p>
      )}
    </Card>
  );
}

function EvidenceList({ run }: { run: ExecutionPayload["run"] }) {
  const citations = run.citations || [];
  const retrieving = run.state === "RETRIEVING";
  if (retrieving && citations.length === 0) {
    return <EmptyState title="Retrieving knowledge">Sources appear when retrieval completes.</EmptyState>;
  }
  if (citations.length === 0) {
    return null;
  }
  return (
    <Card>
      <SectionHeader title="Evidence" description={`${citations.length} source${citations.length === 1 ? "" : "s"} retrieved`} />
      <ul className="mt-3 space-y-2">
        {citations.map((citation, index) => (
          <li key={citation.chunkId || index}>
            <Card variant="compact">
              <div className="text-sm text-paper">{citation.documentTitle || "Untitled source"}</div>
              <div className="mt-1 flex flex-wrap gap-2 text-xs text-muted">
                {citation.section && <span>§ {citation.section}</span>}
                {citation.pageNumber != null && <span>Page {citation.pageNumber}</span>}
                {citation.score != null && <span>Similarity {Number(citation.score).toFixed(2)}</span>}
                {citation.chunkId && <Mono>{citation.chunkId}</Mono>}
              </div>
              {citation.quote && <p className="mt-2 text-sm text-muted">{citation.quote}</p>}
            </Card>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function ToolProposalCard({ proposal, includeArguments }: { proposal: NonNullable<ExecutionPayload["proposal"]>; includeArguments: boolean }) {
  const args = proposal.arguments || {};
  return (
    <Card variant="warning">
      <SectionHeader title="Action proposed" />
      <p className="mt-2 text-sm font-medium">{toolDisplayName(proposal.tool)}</p>
      <dl className="mt-3 space-y-2">
        {proposal.classification && <Meta label="Type" value={proposal.classification} />}
        {proposal.risk && (
          <div>
            <dt className="text-xs text-muted">Risk</dt>
            <dd className="mt-1"><RiskBadge level={proposal.risk} /></dd>
          </div>
        )}
        {proposal.policyDecision && <Meta label="Policy" value={proposal.policyDecision} />}
        {proposal.policyCode && <Meta label="Policy code" value={proposal.policyCode} mono />}
        {proposal.tool && <Meta label="Tool" value={proposal.tool} mono />}
      </dl>
      {includeArguments && Object.keys(args).length > 0 && (
        <dl className="mt-3 space-y-1 text-sm">
          {Object.entries(args).map(([key, value]) => (
            <div key={key} className="flex flex-wrap gap-2">
              <dt className="text-muted">{labelize(key)}</dt>
              <dd>{String(value)}</dd>
            </div>
          ))}
        </dl>
      )}
      {proposal.policyDecision === "REQUIRE_APPROVAL" && (
        <p className="mt-3 text-sm text-muted">This action requires reviewer approval before execution.</p>
      )}
    </Card>
  );
}

function OutcomeCard({ run }: { run: ExecutionPayload["run"] }) {
  if (!isTerminal(run.state) && run.state !== "APPROVAL_REQUIRED") return null;
  const duration = run.durationMs == null ? null : formatDuration(run.durationMs);
  const tokens = formatTokens(run.cost);
  const cost = formatCost(run.cost);
  return (
    <Card variant={run.state === "COMPLETED" ? "success" : "default"}>
      <SectionHeader title="Outcome" />
      <div className="mt-2 flex flex-wrap gap-2 text-sm text-muted">
        <StatusBadge status={run.state} />
        {duration && <span>{duration}</span>}
        {tokens && <span>{tokens}</span>}
        {cost && <span>{cost}</span>}
      </div>
    </Card>
  );
}

function Meta({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <dt className="text-xs text-muted">{label}</dt>
      <dd className={mono ? "font-mono text-xs text-paper break-all" : "text-sm text-paper break-words"}>{value}</dd>
    </div>
  );
}

function failureCopy(run: ExecutionPayload["run"]): string {
  const category = run.failureCategory ? categoryLabel(run.failureCategory) : "The run failed.";
  return run.errorMessage ? `${category} ${run.errorMessage}` : category;
}

function categoryLabel(category: string): string {
  const labels: Record<string, string> = {
    POLICY_DENIED: "Policy denied.",
    TIMED_OUT: "Model timeout.",
    BUDGET_EXCEEDED: "Budget exceeded.",
    TOOL_ERROR: "Tool validation failure.",
    INTERNAL: "Internal error.",
    APPROVAL_EXPIRED: "Approval expired.",
  };
  return labels[category] || `${category.replaceAll("_", " ")}.`;
}

function labelize(key: string): string {
  return key.replaceAll("_", " ").replace(/^\w/, (letter) => letter.toUpperCase());
}
