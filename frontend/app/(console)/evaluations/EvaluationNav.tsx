"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const items = [
  ["/evaluations", "Overview"],
  ["/evaluations/suites", "Suites & cases"],
  ["/evaluations/runs", "Runs & history"],
  ["/evaluations/compare", "Compare"],
];

export function EvaluationNav() {
  const pathname = usePathname();
  return (
    <nav aria-label="Evaluation sections" className="mb-5 flex gap-1 overflow-x-auto border-b border-line">
      {items.map(([href, label]) => {
        const active = href === "/evaluations" ? pathname === href : pathname.startsWith(href);
        return (
          <Link
            key={href}
            href={href}
            aria-current={active ? "page" : undefined}
            className={`whitespace-nowrap border-b-2 px-3 py-2 text-sm ${active ? "border-accent text-paper" : "border-transparent text-muted hover:text-paper"}`}
          >
            {label}
          </Link>
        );
      })}
    </nav>
  );
}
