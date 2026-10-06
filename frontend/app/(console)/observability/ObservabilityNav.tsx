"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

export function ObservabilityNav() {
  const path = usePathname();
  const items = [
    { href: "/observability", label: "Overview", exact: true },
    { href: "/observability/traces", label: "Traces", exact: false },
    { href: "/observability/errors", label: "Errors", exact: false },
  ];
  return (
    <nav aria-label="Observability sections" className="flex flex-wrap gap-2">
      {items.map((item) => {
        const active = item.exact ? path === item.href : path.startsWith(item.href);
        return (
          <Link key={item.href} href={item.href} className={`rounded-md px-3 py-1.5 text-sm ${active ? "bg-elevated text-paper" : "text-muted hover:text-paper"}`}>
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}
