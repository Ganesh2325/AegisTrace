import type { ReactNode } from "react";

export function PageHeader({
  eyebrow,
  title,
  description,
  actions,
  status,
}: {
  eyebrow?: string;
  title: string;
  description?: string;
  actions?: ReactNode;
  status?: ReactNode;
}) {
  return (
    <header className="flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        {eyebrow && <p className="text-[11px] font-medium uppercase tracking-[0.14em] text-muted">{eyebrow}</p>}
        <div className="mt-1 flex flex-wrap items-center gap-2">
          <h1 className="text-xl font-semibold tracking-tight text-paper">{title}</h1>
          {status}
        </div>
        {description && <p className="mt-1 max-w-2xl text-sm leading-5 text-muted">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </header>
  );
}

export function SectionHeader({ title, description, action }: { title: string; description?: string; action?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-2">
      <div>
        <h2 className="text-sm font-medium text-paper">{title}</h2>
        {description && <p className="mt-0.5 text-xs text-muted">{description}</p>}
      </div>
      {action}
    </div>
  );
}

export function Page({ width = "wide", children }: { width?: "standard" | "wide" | "full" | "focus"; children: ReactNode }) {
  const max = { standard: "max-w-3xl", wide: "max-w-[72rem]", full: "max-w-none", focus: "max-w-2xl" }[width];
  return <div className={`${max} space-y-5`}>{children}</div>;
}
