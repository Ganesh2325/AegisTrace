export const apiBase = process.env.NEXT_PUBLIC_API_BASE || "/backend";

export class ApiError extends Error {
  status: number;
  code?: string;

  constructor(message: string, status: number, code?: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export type ApiRequestInit = RequestInit & { timeoutMs?: number };

export async function api<T>(path: string, init: ApiRequestInit = {}): Promise<T> {
  const { timeoutMs = 15_000, ...requestInit } = init;
  const headers = new Headers(init.headers);
  if (init.body && !(init.body instanceof FormData) && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  const controller = new AbortController();
  let timedOut = false;
  const forwardAbort = () => controller.abort(init.signal?.reason);
  if (init.signal?.aborted) forwardAbort();
  else init.signal?.addEventListener("abort", forwardAbort, { once: true });
  const timer = globalThis.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, Math.max(1, timeoutMs));
  try {
    const response = await fetch(`${apiBase}${path}`, {
      ...requestInit,
      headers,
      signal: controller.signal,
      credentials: "include",
    });
    const text = await response.text();
    let data: any = null;
    if (text) {
      try {
        data = JSON.parse(text);
      } catch {
        if (!response.ok) {
          throw new ApiError(
            response.status >= 500 ? "The service is temporarily unavailable." : response.statusText || "Request failed.",
            response.status,
            response.status >= 500 ? "DEPENDENCY_UNAVAILABLE" : undefined,
          );
        }
        throw new ApiError("The server returned an invalid response.", 502, "DEPENDENCY_UNAVAILABLE");
      }
    }
    if (!response.ok) {
      throw new ApiError(data?.message || response.statusText, response.status, data?.code);
    }
    return data as T;
  } catch (error) {
    if (timedOut) throw new ApiError("The request timed out. Try again.", 408, "TIMEOUT");
    throw error;
  } finally {
    globalThis.clearTimeout(timer);
    init.signal?.removeEventListener("abort", forwardAbort);
  }
}

export type Membership = { workspaceId: string; role: string; workspaceName?: string };
export type Me = { id: string; email: string; displayName: string; environment?: string; memberships: Membership[] };

export function roleOf(me: Me | null): string {
  return me?.memberships[0]?.role || "";
}
