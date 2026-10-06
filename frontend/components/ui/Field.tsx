"use client";

import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from "react";
import { cn } from "./cn";

const control = "w-full rounded-md border border-line bg-canvas px-3 py-2 text-sm text-paper placeholder:text-faint disabled:cursor-not-allowed disabled:opacity-50";

export function FieldGroup({ legend, children }: { legend?: string; children: ReactNode }) {
  return (
    <fieldset className="space-y-3">
      {legend && <legend className="text-sm font-medium text-paper">{legend}</legend>}
      {children}
    </fieldset>
  );
}

export function Label({ htmlFor, children }: { htmlFor?: string; children: ReactNode }) {
  return <label htmlFor={htmlFor} className="block text-sm font-medium text-paper">{children}</label>;
}

export function HelpText({ children }: { children: ReactNode }) {
  return <p className="text-xs leading-4 text-muted">{children}</p>;
}

export function FormError({ children }: { children: ReactNode }) {
  return <p className="text-sm text-danger" role="alert">{children}</p>;
}

export function TextField({ id, label, hint, error, className, ...props }: InputHTMLAttributes<HTMLInputElement> & { label: string; hint?: string; error?: string }) {
  const generated = useId();
  const fieldId = id || props.name || generated;
  return (
    <div className="space-y-1.5">
      <Label htmlFor={fieldId}>{label}</Label>
      <input id={fieldId} className={cn(control, error && "border-danger", className)} aria-invalid={error ? true : undefined} aria-describedby={error ? `${fieldId}-error` : undefined} {...props} />
      {hint && <HelpText>{hint}</HelpText>}
      {error && <p id={`${fieldId}-error`} className="text-sm text-danger" role="alert">{error}</p>}
    </div>
  );
}

export function TextAreaField({ id, label, hint, error, className, ...props }: TextareaHTMLAttributes<HTMLTextAreaElement> & { label: string; hint?: string; error?: string }) {
  const generated = useId();
  const fieldId = id || props.name || generated;
  return (
    <div className="space-y-1.5">
      <Label htmlFor={fieldId}>{label}</Label>
      <textarea id={fieldId} className={cn(control, "min-h-32 leading-6", error && "border-danger", className)} aria-invalid={error ? true : undefined} aria-describedby={error ? `${fieldId}-error` : undefined} {...props} />
      {hint && <HelpText>{hint}</HelpText>}
      {error && <p id={`${fieldId}-error`} className="text-sm text-danger" role="alert">{error}</p>}
    </div>
  );
}

export function SelectField({ id, label, children, ...props }: SelectHTMLAttributes<HTMLSelectElement> & { label: string }) {
  const generated = useId();
  const fieldId = id || props.name || generated;
  return (
    <div className="space-y-1.5">
      <Label htmlFor={fieldId}>{label}</Label>
      <select id={fieldId} className={control} {...props}>{children}</select>
    </div>
  );
}

export function CheckboxField({ label, ...props }: InputHTMLAttributes<HTMLInputElement> & { label: string }) {
  return (
    <label className="flex items-center gap-2 text-sm text-paper">
      <input type="checkbox" className="h-4 w-4 rounded border-line bg-canvas accent-accent" {...props} />
      {label}
    </label>
  );
}

export function RadioField({ label, ...props }: InputHTMLAttributes<HTMLInputElement> & { label: string }) {
  return (
    <label className="flex items-center gap-2 text-sm text-paper">
      <input type="radio" className="h-4 w-4 border-line bg-canvas accent-accent" {...props} />
      {label}
    </label>
  );
}

export function SwitchField({ label, ...props }: InputHTMLAttributes<HTMLInputElement> & { label: string }) {
  return (
    <label className="flex items-center justify-between gap-3 text-sm text-paper">
      {label}
      <input type="checkbox" role="switch" className="h-4 w-7 appearance-none rounded-full border border-line bg-canvas checked:bg-accent" {...props} />
    </label>
  );
}
