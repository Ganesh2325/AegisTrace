import assert from "node:assert/strict";
import test from "node:test";

import { api, ApiError } from "./api.ts";

test("api converts its bounded timeout into a typed error", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = ((_url: string | URL | Request, init?: RequestInit) => new Promise((_resolve, reject) => {
    init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")), { once: true });
  })) as typeof fetch;
  try {
    await assert.rejects(
      api("/slow", { timeoutMs: 5 }),
      (error: unknown) => error instanceof ApiError && error.code === "TIMEOUT" && error.status === 408,
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("api forwards caller cancellation without misclassifying it as timeout", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = ((_url: string | URL | Request, init?: RequestInit) => new Promise((_resolve, reject) => {
    init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")), { once: true });
  })) as typeof fetch;
  const controller = new AbortController();
  try {
    const request = api("/cancelled", { signal: controller.signal, timeoutMs: 1_000 });
    controller.abort();
    await assert.rejects(request, (error: unknown) => error instanceof DOMException && error.name === "AbortError");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("api normalizes non-JSON dependency failures", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async () => new Response("Internal Server Error", {
    status: 500,
    statusText: "Internal Server Error",
  })) as typeof fetch;
  try {
    await assert.rejects(
      api("/unavailable"),
      (error: unknown) => error instanceof ApiError
        && error.code === "DEPENDENCY_UNAVAILABLE"
        && error.status === 500
        && error.message === "The service is temporarily unavailable.",
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});
