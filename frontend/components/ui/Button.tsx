"use client";

import Link from "next/link";
import type { ButtonHTMLAttributes, ReactNode } from "react";
import { buttonClass, type ButtonVariant } from "./buttonStyles";
import { Icon } from "./Icon";

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  loading?: boolean;
  loadingLabel?: string;
};

export function Button({ variant = "primary", loading, loadingLabel = "Working…", className, children, disabled, ...props }: Props) {
  return (
    <button {...props} className={buttonClass(variant, className)} disabled={disabled || loading} aria-busy={loading || undefined}>
      {loading && <Icon name="spinner" />}
      {loading ? loadingLabel : children}
    </button>
  );
}

export function ButtonLink({ href, variant = "primary", className, children }: { href: string; variant?: ButtonVariant; className?: string; children: ReactNode }) {
  return <Link href={href} className={buttonClass(variant, className)}>{children}</Link>;
}

export function IconButton({ label, className, children, ...props }: Props & { label: string }) {
  return (
    <Button {...props} className={`h-8 w-8 px-0 ${className || ""}`} aria-label={label}>
      {children}
    </Button>
  );
}
