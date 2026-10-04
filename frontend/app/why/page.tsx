import Link from "next/link";

export default function WhyPage() {
  return (
    <main className="mx-auto max-w-3xl px-6 py-12">
      <p className="kicker">Recruiter note</p>
      <h1 className="mt-2 text-4xl font-semibold">Why AegisTrace is different</h1>
      <p className="mt-4 text-lg text-mist">It is a control system for one support agent. The model can suggest a ticket. It cannot authorize one.</p>
      <ul className="mt-8 space-y-3 text-sm leading-6">
        <li>It is not a chatbot. The product record is a run, not a message bubble.</li>
        <li>It is not only retrieval. Answers cite chunks or they abstain.</li>
        <li>It is not only an agent. Write tools stop at a human approval.</li>
        <li>It is not only a dashboard. The charts read stored runs, traces, and evaluations.</li>
      </ul>
      <p className="mt-8 font-mono text-sm leading-7">Agent + RAG + policy + human approval + idempotent execution + audit + observability + evaluation + security</p>
      <Link className="btn-primary mt-8" href="/login">Open the console</Link>
    </main>
  );
}
