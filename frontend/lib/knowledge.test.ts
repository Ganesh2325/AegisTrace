import assert from "node:assert/strict";
import { fileKind, formatBytes, knowledgeVersionLabel, scoreLabel } from "./knowledge.ts";

assert.equal(fileKind("application/pdf"), "PDF");
assert.equal(fileKind("text/markdown"), "Markdown");
assert.equal(fileKind("text/plain"), "Text");
assert.equal(formatBytes(512), "512 B");
assert.equal(formatBytes(2048), "2 KB");
assert.equal(scoreLabel(0.0312), "0.0312");
assert.equal(knowledgeVersionLabel(2), "v2");
assert.equal(knowledgeVersionLabel(null, "MISSING"), "MISSING");

console.log("knowledge tests passed");
