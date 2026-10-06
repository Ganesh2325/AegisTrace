export type Role = "OPERATOR" | "DEVELOPER" | "REVIEWER" | "ADMIN";

export type Capability =
  | "overview.read"
  | "runs.read"
  | "runs.create"
  | "approvals.read"
  | "agents.read"
  | "agents.configure"
  | "knowledge.read"
  | "evaluation.read"
  | "observability.read"
  | "audit.read"
  | "admin.console";

export type NavIcon =
  | "overview"
  | "run"
  | "approval"
  | "agent"
  | "knowledge"
  | "evaluation"
  | "observability"
  | "audit"
  | "admin";

export type NavGroupKey = "workspace" | "build" | "intelligence" | "governance" | "system";

export type NavItem = {
  label: string;
  href: string;
  icon: NavIcon;
  capability: Capability;
  description: string;
  group: NavGroupKey;
  match: (pathname: string) => boolean;
};

export const NAV_GROUPS: { key: NavGroupKey; label: string }[] = [
  { key: "workspace", label: "Workspace" },
  { key: "build", label: "Build" },
  { key: "intelligence", label: "Intelligence" },
  { key: "governance", label: "Governance" },
  { key: "system", label: "System" },
];

const ALL: Capability[] = [
  "overview.read",
  "runs.read",
  "runs.create",
  "approvals.read",
  "agents.read",
  "agents.configure",
  "knowledge.read",
  "evaluation.read",
  "observability.read",
  "audit.read",
  "admin.console",
];

const ROLE_CAPABILITIES: Record<Role, Capability[]> = {
  OPERATOR: ["overview.read", "runs.read", "runs.create", "agents.read", "knowledge.read", "observability.read"],
  REVIEWER: ["overview.read", "runs.read", "approvals.read", "agents.read", "observability.read"],
  DEVELOPER: ["overview.read", "runs.read", "agents.read", "agents.configure", "knowledge.read", "evaluation.read", "observability.read", "audit.read", "admin.console"],
  ADMIN: ALL,
};

export const NAV_ITEMS: NavItem[] = [
  { label: "Overview", href: "/", icon: "overview", capability: "overview.read", description: "Workspace operations", group: "workspace", match: (path) => path === "/" },
  { label: "Support run", href: "/runs/new", icon: "run", capability: "runs.create", description: "Start a support run", group: "workspace", match: (path) => path === "/runs/new" || path.startsWith("/runs/") },
  { label: "Approvals", href: "/approvals", icon: "approval", capability: "approvals.read", description: "Pending write approvals", group: "workspace", match: (path) => path === "/approvals" || path.startsWith("/approvals/") },
  { label: "Agents", href: "/agents", icon: "agent", capability: "agents.read", description: "Agent versions and configuration", group: "build", match: (path) => path === "/agents" || path.startsWith("/agents/") },
  { label: "Knowledge", href: "/knowledge", icon: "knowledge", capability: "knowledge.read", description: "Document corpus", group: "build", match: (path) => path === "/knowledge" || path.startsWith("/knowledge/") },
  { label: "Evaluation", href: "/evaluations", icon: "evaluation", capability: "evaluation.read", description: "Heuristic evaluation checks", group: "intelligence", match: (path) => path === "/evaluations" || path.startsWith("/evaluations/") },
  { label: "Observability", href: "/observability", icon: "observability", capability: "observability.read", description: "Metrics and trace tools", group: "intelligence", match: (path) => path === "/observability" || path.startsWith("/observability/") },
  { label: "Audit", href: "/audit", icon: "audit", capability: "audit.read", description: "Append-only audit history", group: "governance", match: (path) => path === "/audit" || path.startsWith("/audit/") },
  { label: "Administration", href: "/admin", icon: "admin", capability: "admin.console", description: "Membership and policy dry run", group: "system", match: (path) => path === "/admin" || path.startsWith("/admin/") },
];

export type Command = { id: string; label: string; href: string; keywords: string };

const EXTRA_COMMANDS: Command[] = [
  { id: "why", label: "Why this exists", href: "/why", keywords: "about help product" },
  { id: "overview-refresh", label: "Refresh Overview", href: "/", keywords: "refresh operations overview" },
];

export function capabilitiesFor(role: string): Capability[] {
  if (role in ROLE_CAPABILITIES) return ROLE_CAPABILITIES[role as Role];
  return [];
}

export function canAccess(role: string, capability: Capability): boolean {
  return capabilitiesFor(role).includes(capability);
}

export function visibleNav(role: string): NavItem[] {
  return NAV_ITEMS.filter((item) => canAccess(role, item.capability));
}

export function visibleGroups(role: string) {
  const items = visibleNav(role);
  return NAV_GROUPS.map((group) => ({ ...group, items: items.filter((item) => item.group === group.key) })).filter((group) => group.items.length > 0);
}

export function activeItem(role: string, pathname: string): NavItem | null {
  const matches = visibleNav(role).filter((item) => item.match(pathname));
  matches.sort((a, b) => b.href.length - a.href.length);
  return matches[0] ?? null;
}

export function capabilityForPath(pathname: string): Capability | null {
  if (pathname === "/") return "overview.read";
  if (pathname === "/runs/new") return "runs.create";
  if (pathname.startsWith("/runs/")) return "runs.read";
  const item = NAV_ITEMS.find((entry) => entry.href !== "/" && entry.href !== "/runs/new" && (pathname === entry.href || pathname.startsWith(`${entry.href}/`)));
  return item ? item.capability : null;
}

export type RouteDecision = { kind: "allow" } | { kind: "forbidden"; capability: Capability } | { kind: "unknown" };

export function decideRoute(role: string, pathname: string): RouteDecision {
  const capability = capabilityForPath(pathname);
  if (!capability) return { kind: "unknown" };
  if (!canAccess(role, capability)) return { kind: "forbidden", capability };
  return { kind: "allow" };
}

export function commandsFor(role: string, query: string): Command[] {
  const q = query.trim().toLowerCase();
  const nav = visibleNav(role).map((item) => ({
    id: item.href,
    label: item.label,
    href: item.href,
    keywords: `${item.label} ${item.description}`.toLowerCase(),
  }));
  return [...nav, ...EXTRA_COMMANDS].filter((command) => !q || command.keywords.includes(q) || command.label.toLowerCase().includes(q));
}

export function environmentLabel(value: string | undefined): string {
  const raw = (value || "").trim().toLowerCase();
  if (raw === "dev" || raw === "local" || raw === "development") return "LOCAL";
  if (raw === "test") return "TEST";
  if (raw === "demo" || raw === "staging" || raw === "stage") return "DEMO";
  if (raw === "prod" || raw === "production") return "PRODUCTION";
  if (!raw) return "UNKNOWN";
  return raw.toUpperCase();
}

export function breadcrumb(role: string, pathname: string): { href?: string; label: string }[] {
  const agent = pathname.match(/^\/agents\/([^/]+)$/);
  if (agent) return [{ href: "/agents", label: "Agents" }, { label: "Agent" }];
  const run = pathname.match(/^\/runs\/([^/]+)$/);
  if (!run || run[1] === "new") return [];
  const parent = canAccess(role, "runs.create") ? [{ href: "/runs/new", label: "Support run" }] : [];
  return [...parent, { label: "Run" }];
}
