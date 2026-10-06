import { ButtonLink } from "../../components/ui/Button";

export default function ConsoleNotFound() {
  return (
    <div className="max-w-xl rounded-md border border-dashed border-line px-4 py-6">
      <h1 className="text-base font-semibold text-paper">Page not found</h1>
      <p className="mt-1 text-sm text-muted">The requested AegisTrace page does not exist.</p>
      <div className="mt-3"><ButtonLink href="/" variant="secondary">Return to Overview</ButtonLink></div>
    </div>
  );
}
