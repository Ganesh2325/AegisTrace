"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { activeItem, breadcrumb, commandsFor, decideRoute, environmentLabel, visibleGroups, type Command } from "../lib/access";
import { api, ApiError, roleOf, type Me } from "../lib/api";
import { clearSession, writeSession } from "../lib/session";
import { Button } from "./ui/Button";
import { Icon } from "./ui/Icon";
import { ErrorState, ForbiddenState, PageLoading } from "./ui/States";
import { ToastProvider } from "./ui/Toast";

export function Shell({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [ready, setReady] = useState(false);
  const [sessionError, setSessionError] = useState<string | null>(null);
  const [sessionAttempt, setSessionAttempt] = useState(0);
  const [leaving, setLeaving] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [drawer, setDrawer] = useState(false);
  const [palette, setPalette] = useState(false);
  const [query, setQuery] = useState("");
  const [cursor, setCursor] = useState(0);
  const [menu, setMenu] = useState<"user" | "notes" | null>(null);
  const pathname = usePathname();
  const router = useRouter();
  const searchRef = useRef<HTMLInputElement>(null);
  const drawerRef = useRef<HTMLElement>(null);

  useEffect(() => {
    let stop = false;
    const controller = new AbortController();
    setReady(false);
    setSessionError(null);
    api<Me>("/api/v1/auth/me", { signal: controller.signal }).then((row) => {
      if (stop) return;
      writeSession(row);
      setMe(row);
      setReady(true);
    }).catch((error) => {
      if (stop) return;
      if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
        clearSession();
        router.replace("/login");
        return;
      }
      setSessionError(error instanceof Error ? error.message : "The session check failed.");
      setReady(true);
    });
    return () => {
      stop = true;
      controller.abort();
    };
  }, [router, sessionAttempt]);

  useEffect(() => {
    setCollapsed(window.localStorage.getItem("aegis.nav.collapsed") === "1");
  }, []);

  useEffect(() => { setDrawer(false); setMenu(null); }, [pathname]);

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setPalette(true);
        setQuery("");
        setCursor(0);
      }
      if (event.key === "Escape") {
        setPalette(false);
        setMenu(null);
        setDrawer(false);
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  useEffect(() => {
    if (palette) searchRef.current?.focus();
  }, [palette]);

  useEffect(() => {
    if (!drawer) return;
    drawerRef.current?.querySelector<HTMLElement>("a")?.focus();
  }, [drawer]);

  if (!ready) return <PageLoading label="Checking session…" />;
  if (sessionError || !me) {
    return (
      <main className="mx-auto max-w-xl px-6 py-16">
        <ErrorState title="Session check unavailable" onRetry={() => setSessionAttempt((value) => value + 1)}>
          {sessionError || "The session could not be loaded."} Your session was not cleared.
        </ErrorState>
      </main>
    );
  }

  const role = roleOf(me);
  const groups = visibleGroups(role);
  const current = activeItem(role, pathname);
  const decision = decideRoute(role, pathname);
  const crumbs = breadcrumb(role, pathname);
  const commands = commandsFor(role, query);
  const workspace = workspaceLabel(me);
  const environment = environmentLabel(me.environment);

  function toggleCollapsed() {
    setCollapsed((value) => {
      window.localStorage.setItem("aegis.nav.collapsed", value ? "0" : "1");
      return !value;
    });
  }

  async function logout() {
    if (leaving) return;
    setLeaving(true);
    clearSession();
    setMe(null);
    setReady(false);
    try {
      await api("/api/v1/auth/logout", { method: "POST" });
    } finally {
      router.replace("/login");
    }
  }

  function go(command: Command) {
    setPalette(false);
    setQuery("");
    router.push(command.href);
  }

  return (
    <ToastProvider>
      <div className="h-screen overflow-hidden bg-canvas md:grid md:grid-cols-[auto_minmax(0,1fr)]">
        <aside className={`hidden h-screen shrink-0 flex-col border-r border-line bg-surface md:flex ${collapsed ? "w-14" : "w-56"}`}>
          <Brand workspace={workspace} collapsed={collapsed} />
          <SidebarNav groups={groups} current={current?.href} collapsed={collapsed} />
          <button type="button" className="m-2 rounded-md px-2 py-1.5 text-left text-xs text-muted hover:bg-white/5 hover:text-paper" onClick={toggleCollapsed} aria-pressed={collapsed} aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}>
            {collapsed ? "»" : "Collapse"}
          </button>
        </aside>
        {drawer && (
          <div className="fixed inset-0 z-40 bg-canvas/70 md:hidden" onMouseDown={() => setDrawer(false)}>
            <aside ref={drawerRef} className="flex h-full w-64 flex-col border-r border-line bg-surface" role="dialog" aria-modal="true" aria-label="Navigation" onMouseDown={(event) => event.stopPropagation()}>
              <Brand workspace={workspace} collapsed={false} />
              <SidebarNav groups={groups} current={current?.href} collapsed={false} onNavigate={() => setDrawer(false)} />
            </aside>
          </div>
        )}
        <div className="relative flex h-screen min-w-0 flex-col">
          <header className="flex h-12 shrink-0 items-center gap-2 border-b border-line px-3">
            <button type="button" className="rounded-md p-1.5 text-muted hover:bg-white/5 md:hidden" aria-label="Open navigation" onClick={() => setDrawer(true)}>
              <Icon name="menu" />
            </button>
            {crumbs.length > 0 && (
              <nav aria-label="Breadcrumb" className="min-w-0 text-xs text-muted">
                <ol className="flex items-center gap-1">
                  {crumbs.map((crumb, index) => (
                    <li key={crumb.label} className="flex items-center gap-1">
                      {index > 0 && <span aria-hidden="true">/</span>}
                      {crumb.href ? <Link className="hover:text-paper" href={crumb.href}>{crumb.label}</Link> : <span className="text-paper">{crumb.label}</span>}
                    </li>
                  ))}
                </ol>
              </nav>
            )}
            <button type="button" className="ml-auto inline-flex items-center gap-2 rounded-md border border-line px-2 py-1 text-xs text-muted hover:text-paper" aria-label="Jump to a page" aria-keyshortcuts="Control+K Meta+K" onClick={() => { setPalette(true); setQuery(""); setCursor(0); }}>
              <Icon name="search" />
              <span className="hidden sm:inline">Jump to</span>
            </button>
            <span className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-medium ${environment === "PRODUCTION" ? "border-danger/40 text-danger" : "border-warning/40 text-warning"}`} role="status">
              <Icon name="dot" />
              {environment}
            </span>
            <button type="button" className="rounded-md p-1.5 text-muted hover:bg-white/5" aria-expanded={menu === "notes"} aria-label="Notifications" onClick={() => setMenu(menu === "notes" ? null : "notes")}>
              <Icon name="bell" />
            </button>
            <button type="button" className="rounded-md px-2 py-1 text-left text-xs hover:bg-white/5" aria-expanded={menu === "user"} aria-haspopup="menu" onClick={() => setMenu(menu === "user" ? null : "user")}>
              <span className="block text-paper">{me.displayName}</span>
              <span className="font-mono text-[10px] text-muted">{role}</span>
            </button>
          </header>
          {menu === "notes" && (
            <div className="absolute right-3 top-12 z-30 w-64 rounded-md border border-line bg-elevated p-3 text-sm shadow-panel" role="status">
              No notifications
            </div>
          )}
          {menu === "user" && (
            <div className="absolute right-3 top-12 z-30 w-64 rounded-md border border-line bg-elevated p-3 text-sm shadow-panel" role="menu">
              <div className="text-paper">{me.displayName}</div>
              <div className="mt-0.5 font-mono text-[11px] text-muted">{role}</div>
              <div className="mt-2 text-xs text-muted">Workspace</div>
              <div className="text-sm text-paper">{workspace}</div>
              <Link className="mt-3 block text-sm text-info hover:underline" href="/why" role="menuitem">Why this exists</Link>
              <Button className="mt-3" variant="ghost" loading={leaving} loadingLabel="Signing out…" onClick={logout}>Log out</Button>
            </div>
          )}
          <div className="min-h-0 flex-1 overflow-y-auto p-4 md:p-6">
            {decision.kind === "forbidden" ? <ForbiddenState capability={decision.capability} /> : children}
          </div>
        </div>
        {palette && (
          <div className="fixed inset-0 z-50 flex items-start justify-center bg-canvas/70 p-4 pt-[12vh]" onMouseDown={() => setPalette(false)}>
            <div className="w-full max-w-lg rounded-md border border-line bg-elevated shadow-panel" role="dialog" aria-modal="true" aria-label="Jump to a page" onMouseDown={(event) => event.stopPropagation()}>
              <input
                ref={searchRef}
                className="w-full border-b border-line bg-transparent px-3 py-2 text-sm outline-none"
                aria-label="Jump to a page"
                placeholder="Jump to a page"
                value={query}
                onChange={(event) => { setQuery(event.target.value); setCursor(0); }}
                onKeyDown={(event) => {
                  if (event.key === "ArrowDown") { event.preventDefault(); setCursor((value) => Math.min(value + 1, Math.max(commands.length - 1, 0))); }
                  if (event.key === "ArrowUp") { event.preventDefault(); setCursor((value) => Math.max(value - 1, 0)); }
                  if (event.key === "Enter" && commands[cursor]) go(commands[cursor]);
                }}
              />
              <ul role="listbox" aria-label="Pages" className="max-h-72 overflow-auto py-1">
                {commands.length === 0 && <li className="px-3 py-2 text-sm text-muted">No matching pages</li>}
                {commands.map((command, index) => (
                  <li key={command.id} role="option" aria-selected={index === cursor}>
                    <button type="button" className={`block w-full px-3 py-2 text-left text-sm ${index === cursor ? "bg-white/10 text-paper" : "text-muted"}`} onMouseEnter={() => setCursor(index)} onClick={() => go(command)}>
                      {command.label}
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        )}
      </div>
    </ToastProvider>
  );
}

function workspaceLabel(me: Me) {
  const names = me.memberships.map((item) => item.workspaceName || item.workspaceId).filter(Boolean);
  if (names.length === 0) return "No workspace";
  return names.join(", ");
}

function Brand({ workspace, collapsed }: { workspace: string; collapsed: boolean }) {
  return (
    <Link href="/" className="block px-3 py-3">
      <div className="text-sm font-semibold tracking-tight">{collapsed ? "A" : "AegisTrace"}</div>
      {!collapsed && <div className="kicker">AI agent control</div>}
      {!collapsed && <div className="mt-1 truncate text-xs text-muted">{workspace}</div>}
    </Link>
  );
}

function SidebarNav({
  groups,
  current,
  collapsed,
  onNavigate,
}: {
  groups: ReturnType<typeof visibleGroups>;
  current?: string;
  collapsed: boolean;
  onNavigate?: () => void;
}) {
  const labelId = useId();
  return (
    <nav className="min-h-0 flex-1 overflow-y-auto px-2" aria-labelledby={labelId}>
      <h2 id={labelId} className="sr-only">Console</h2>
      {groups.map((group) => (
        <div key={group.key} className="mb-3">
          {!collapsed && <div className="px-2 py-1 text-[10px] font-medium uppercase tracking-[0.14em] text-faint">{group.label}</div>}
          <ul>
            {group.items.map((item) => {
              const active = item.href === current;
              return (
                <li key={item.href}>
                  <Link
                    href={item.href}
                    aria-current={active ? "page" : undefined}
                    title={collapsed ? item.label : undefined}
                    onClick={onNavigate}
                    className={`flex items-center gap-2 rounded-md border-l-2 px-2 py-1.5 text-sm ${active ? "border-accent bg-accent/10 text-paper" : "border-transparent text-muted hover:bg-white/5 hover:text-paper"}`}
                  >
                    <Icon name={item.icon} />
                    {collapsed ? <span className="sr-only">{item.label}</span> : item.label}
                  </Link>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </nav>
  );
}
