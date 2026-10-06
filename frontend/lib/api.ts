export const apiBase = process.env.NEXT_PUBLIC_API_BASE || "/backend";

export class ApiError extends Error {
  constructor(message: string, public status: number, public code?: string) {
    super(message);
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  const response = await fetch(`${apiBase}${path}`, { ...init, headers, credentials: "include" });
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new ApiError(data?.message || response.statusText, response.status, data?.code);
  }
  return data as T;
}

export type Membership = { workspaceId: string; role: string; workspaceName?: string };
export type Me = { id: string; email: string; displayName: string; environment?: string; memberships: Membership[] };

export function roleOf(me: Me | null): string {
  return me?.memberships[0]?.role || "";
}
