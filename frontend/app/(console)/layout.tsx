"use client";

import { Shell } from "../../components/Shell";
import type { ReactNode } from "react";

export default function ConsoleLayout({ children }: { children: ReactNode }) {
  return <Shell>{children}</Shell>;
}
