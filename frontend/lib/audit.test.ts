import assert from "node:assert/strict";
import { actorLabel } from "./audit.ts";

assert.equal(actorLabel({ actorKind: "SYSTEM", actorEmail: null, actorId: null }), "System");
assert.equal(actorLabel({ actorKind: "HUMAN", actorEmail: "a@b.test", actorId: "1" }), "a@b.test");

console.log("audit helper tests passed");
