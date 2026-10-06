import { cn } from "./cn";

export type ButtonVariant = "primary" | "secondary" | "tertiary" | "ghost" | "danger";

export function buttonClass(variant: ButtonVariant = "primary", className?: string) {
  const base = "inline-flex items-center justify-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition-colors duration-150 disabled:cursor-not-allowed disabled:opacity-50";
  const variants: Record<ButtonVariant, string> = {
    primary: "bg-accent text-canvas hover:bg-accent/90 active:bg-accent/80",
    secondary: "border border-line-strong bg-elevated text-paper hover:bg-overlay",
    tertiary: "text-paper hover:bg-white/5",
    ghost: "border border-line text-paper hover:bg-white/5",
    danger: "bg-danger text-white hover:bg-danger/90 active:bg-danger/80",
  };
  return cn(base, variants[variant], className);
}
