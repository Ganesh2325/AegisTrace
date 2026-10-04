"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { api, Me, roleOf } from "../lib/api";

const links = [
  ["/", "Dashboard"],
  ["/runs/new", "Support run"],
  ["/approvals", "Approvals"],
  ["/agents", "Agents"],
  ["/knowledge", "Knowledge"],
  ["/evaluations", "Evaluation"],
  ["/observability", "Observability"],
  ["/audit", "Audit"],
  ["/admin", "Admin"],
  ["/why", "Why this exists"],
];

export function Shell({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const pathname = usePathname();
  const router = useRouter();

  useEffect(() => {
    api<Me>("/api/v1/auth/me").then(setMe).catch(() => router.replace("/login"));
  }, [router]);

  if (!me) {
    return <div className="p-8 text-mist">Checking session…</div>;
  }

  return (
    <div className="min-h-screen md:grid md:grid-cols-[220px_1fr]">
      <aside className="border-b border-line p-4 md:border-b-0 md:border-r">
        <Link href="/" className="block">
          <div className="text-lg font-semibold">AegisTrace</div>
          <div className="kicker">Support control</div>
        </Link>
        <nav className="mt-6 flex gap-2 overflow-auto md:flex-col">
          {links.map(([href, label]) => (
            <Link key={href} href={href} className={`rounded px-2 py-1.5 text-sm ${pathname === href ? "bg-white/10 text-paper" : "text-mist hover:text-paper"}`}>
              {label}
            </Link>
          ))}
        </nav>
        <div className="mt-6 text-xs text-mist">
          <div>{me.displayName}</div>
          <div className="font-mono">{roleOf(me)}</div>
          <button className="btn-ghost mt-3" onClick={async () => { await api("/api/v1/auth/logout", { method: "POST" }); router.replace("/login"); }}>
            Log out
          </button>
        </div>
      </aside>
      <main className="p-4 md:p-6">{children}</main>
    </div>
  );
}
