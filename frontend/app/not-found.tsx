import { ButtonLink } from "../components/ui/Button";

export default function NotFound() {
  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-6">
      <h1 className="text-xl font-semibold">Page not found</h1>
      <p className="mt-2 text-sm text-muted">The requested AegisTrace page does not exist.</p>
      <div className="mt-4"><ButtonLink href="/login" variant="secondary">Return to sign in</ButtonLink></div>
    </main>
  );
}
