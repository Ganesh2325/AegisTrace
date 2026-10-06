import { ButtonLink } from "../../components/ui/Button";

export default function WhyPage() {
  return (
    <main className="mx-auto max-w-3xl space-y-6 px-6 py-12">
      <p className="text-[11px] font-medium uppercase tracking-[0.14em] text-muted">Recruiter note</p>
      <h1 className="text-3xl font-semibold tracking-tight">Why AegisTrace is different</h1>
      <p className="max-w-2xl text-base text-muted">It is a control system for one support agent. The model can suggest a ticket. It cannot authorize one.</p>
      <ul className="space-y-2 text-sm leading-6">
        <li>The product record is a run, not a message bubble.</li>
        <li>Answers cite chunks, or they abstain.</li>
        <li>Write tools stop at a human approval.</li>
        <li>The numbers read stored runs, traces, and evaluations.</li>
      </ul>
      <p className="font-mono text-sm leading-7 text-muted">Agent + RAG + policy + human approval + idempotent execution + audit + observability + evaluation + security</p>
      <ButtonLink href="/login">Open the console</ButtonLink>
    </main>
  );
}
