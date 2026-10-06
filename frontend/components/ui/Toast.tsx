"use client";

import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";

type Tone = "success" | "info" | "warning" | "error";
type Toast = { id: number; tone: Tone; text: string };

const ToastContext = createContext<(tone: Tone, text: string) => void>(() => undefined);

export function useToast() {
  return useContext(ToastContext);
}

const tones: Record<Tone, string> = {
  success: "border-success/40 text-success",
  info: "border-info/40 text-info",
  warning: "border-warning/40 text-warning",
  error: "border-danger/40 text-danger",
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<Toast[]>([]);
  const push = useCallback((tone: Tone, text: string) => {
    const id = Date.now();
    setItems((current) => [...current, { id, tone, text }]);
    window.setTimeout(() => setItems((current) => current.filter((item) => item.id !== id)), 4000);
  }, []);
  const value = useMemo(() => push, [push]);
  return (
    <ToastContext.Provider value={value}>
      {children}
      <ol className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-80 max-w-[calc(100vw-2rem)] flex-col gap-2" aria-live="polite">
        {items.map((item) => (
          <li key={item.id} className={`rounded-md border bg-elevated px-3 py-2 text-sm shadow-panel ${tones[item.tone]}`}>{item.text}</li>
        ))}
      </ol>
    </ToastContext.Provider>
  );
}
