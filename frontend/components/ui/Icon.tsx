import type { SVGProps } from "react";

const paths = {
  dot: "M8 4.5a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7Z",
  check: "M3.5 8.5 6.5 11.5 12.5 4.5",
  alert: "M8 2.5 14.5 14H1.5L8 2.5Zm0 4.2v3.2M8 12.2h.01",
  x: "M4 4l8 8M12 4 4 12",
  clock: "M8 4.2v4l2.4 1.4M8 14a6 6 0 1 0 0-12 6 6 0 0 0 0 12Z",
  external: "M6 3.5H3.5V12.5H12.5V10M8.5 3.5H12.5V7.5M12.2 3.8 7 9",
  spinner: "M8 2.5a5.5 5.5 0 1 1-3.9 1.6",
  overview: "M2.5 2.5h4.5v4.5H2.5zM9 2.5h4.5v4.5H9zM2.5 9h4.5v4.5H2.5zM9 9h4.5v4.5H9z",
  run: "M5 3.5v9l7-4.5-7-4.5z",
  approval: "M3 3.5h10v9H3zM5.5 8l1.6 1.6L10.5 6",
  agent: "M8 8.2a2.2 2.2 0 1 0 0-4.4 2.2 2.2 0 0 0 0 4.4zM3.5 13.2c.6-2 2.3-3 4.5-3s3.9 1 4.5 3",
  knowledge: "M3.5 3.2h6.2A2.3 2.3 0 0 1 12 5.5V13H5.2A1.7 1.7 0 0 0 3.5 11.3V3.2zM3.5 11.3h8.5",
  evaluation: "M3.5 4h9M3.5 8h9M3.5 12h6",
  observability: "M2.5 10.5 5.5 7l2.2 2.2L13 4",
  audit: "M8 2.8 13 5v4.2c0 2.6-2 4.4-5 5.5-3-1.1-5-2.9-5-5.5V5l5-2.2z",
  admin: "M3 4.5h10M3 8h10M3 11.5h10M6 4.5v0M10 8v0M7 11.5v0",
  menu: "M3 4.5h10M3 8h10M3 11.5h10",
  bell: "M4.5 11.5h7l-.7-1.2V7.2a2.8 2.8 0 1 0-5.6 0v3.1L4.5 11.5zM7 13h2",
  search: "M7 11.2a4.2 4.2 0 1 0 0-8.4 4.2 4.2 0 0 0 0 8.4zM10.2 10.2 13 13",
  user: "M8 8a2.3 2.3 0 1 0 0-4.6A2.3 2.3 0 0 0 8 8zM3.8 13c.7-2 2.2-3 4.2-3s3.5 1 4.2 3",
} as const;

export function Icon({ name, className }: { name: keyof typeof paths; className?: string }) {
  const spin = name === "spinner";
  return (
    <svg viewBox="0 0 16 16" aria-hidden="true" className={`h-3.5 w-3.5 shrink-0 ${spin ? "animate-spin" : ""} ${className || ""}`} fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d={paths[name]} />
    </svg>
  );
}

export function IconSvg(props: SVGProps<SVGSVGElement>) {
  return <svg viewBox="0 0 16 16" aria-hidden="true" {...props} />;
}
