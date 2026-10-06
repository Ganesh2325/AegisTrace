"use client";

import { Button, ButtonLink } from "../../components/ui/Button";

export default function ConsoleError({ reset }: { error: Error; reset: () => void }) {
  return (
    <div className="max-w-xl rounded-md border border-danger/40 bg-danger/10 px-4 py-4" role="alert">
      <h1 className="text-base font-semibold text-paper">Something went wrong</h1>
      <p className="mt-1 text-sm text-muted">The console could not render this page.</p>
      <div className="mt-3 flex gap-2">
        <Button variant="secondary" onClick={reset}>Try again</Button>
        <ButtonLink href="/" variant="ghost">Return to overview</ButtonLink>
      </div>
    </div>
  );
}
