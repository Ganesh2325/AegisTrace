"use client";

import { useId, useState } from "react";

export function HelpTooltip({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
  const id = useId();
  return (
    <span className="relative inline-flex" onMouseEnter={() => setOpen(true)} onMouseLeave={() => setOpen(false)}>
      <button
        type="button"
        className="inline-flex h-4 w-4 items-center justify-center rounded-full border border-line text-[10px] normal-case tracking-normal text-muted"
        aria-expanded={open}
        aria-describedby={open ? id : undefined}
        aria-label={text}
        onClick={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onKeyDown={(event) => {
          if (event.key === "Escape") setOpen(false);
        }}
      >
        ?
      </button>
      {open && (
        <span id={id} role="tooltip" className="absolute left-0 top-6 z-20 w-64 rounded-md border border-line-strong bg-overlay p-2 text-left text-xs font-normal normal-case leading-4 tracking-normal text-paper shadow-panel">
          {text}
        </span>
      )}
    </span>
  );
}
