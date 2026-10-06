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
assert.equal(rows[0].depth, 0);
assert.equal(rows[1].depth, 1);
assert.ok(rows[1].widthPct > 0);
assert.equal(rows[1].status, "ERROR");

const childFirst = waterfallRows([rows[1], rows[0]]);
assert.equal(childFirst[0].name, "aegistrace.agent.run");
assert.equal(childFirst[1].depth, 1);

const historical = waterfallRows([
  { spanId: "h", parentSpanId: "", name: "llm.generate", service: "control-plane", kind: "TELEMETRY", startUs: 50, durationUs: 10, durationMs: 0.01, start: null, status: "OK", attributes: {} },
]);
assert.equal(historical.length, 1);
assert.equal(historical[0].name, "llm.generate");
assert.equal(historical[0].depth, 0);

console.log("observability tests passed");
