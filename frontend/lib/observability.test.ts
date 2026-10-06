import assert from "node:assert/strict";
import { compactId, parseObsWindow, waterfallRows } from "./observability.ts";

assert.equal(parseObsWindow("1h"), "1H");
assert.equal(parseObsWindow("nope"), "24H");
assert.equal(compactId("abcdefghij"), "abcdefgh…");

const rows = waterfallRows([
  { spanId: "1", parentSpanId: "", name: "aegistrace.agent.run", service: "control-plane", kind: "TELEMETRY", startUs: 1000, durationUs: 1000, durationMs: 1, start: null, status: "OK", attributes: {} },
  { spanId: "2", parentSpanId: "1", name: "aegistrace.runtime.plan", service: "control-plane", kind: "TELEMETRY", startUs: 1200, durationUs: 200, durationMs: 0.2, start: null, status: "ERROR", attributes: { "run.id": "abc" } },
]);
assert.equal(rows.length, 2);
assert.equal(rows[0].offsetPct, 0);
assert.ok(rows[1].widthPct > 0);
assert.equal(rows[1].status, "ERROR");

console.log("observability tests passed");
