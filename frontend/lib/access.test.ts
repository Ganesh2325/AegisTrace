import assert from "node:assert/strict";
import { activeItem, breadcrumb, canAccess, commandsFor, decideRoute, environmentLabel, visibleNav } from "./access.ts";

const labels = (role: string) => visibleNav(role).map((item) => item.label);

assert.deepEqual(labels("OPERATOR"), ["Overview", "Support run", "Agents", "Knowledge", "Observability"]);
assert.deepEqual(labels("REVIEWER"), ["Overview", "Approvals", "Agents", "Observability"]);
assert.deepEqual(labels("DEVELOPER"), ["Overview", "Agents", "Knowledge", "Evaluation", "Observability", "Audit", "Administration"]);
assert.equal(visibleNav("ADMIN").length, 9);
assert.equal(canAccess("OPERATOR", "audit.read"), false);
assert.equal(canAccess("OPERATOR", "admin.console"), false);
assert.equal(canAccess("REVIEWER", "approvals.read"), true);
assert.equal(canAccess("REVIEWER", "approvals.review"), true);
assert.equal(canAccess("OPERATOR", "approvals.review"), false);
assert.equal(canAccess("DEVELOPER", "approvals.review"), false);
assert.equal(canAccess("DEVELOPER", "runs.create"), false);
assert.equal(canAccess("OPERATOR", "agents.configure"), false);
assert.equal(canAccess("REVIEWER", "agents.configure"), false);
assert.equal(canAccess("DEVELOPER", "agents.configure"), true);
assert.equal(canAccess("OPERATOR", "knowledge.manage"), false);
assert.equal(canAccess("DEVELOPER", "knowledge.manage"), true);
assert.equal(canAccess("REVIEWER", "knowledge.read"), false);
assert.equal(commandsFor("DEVELOPER", "upload")[0]?.href, "/knowledge?upload=1");
assert.deepEqual(breadcrumb("REVIEWER", "/approvals/abc").map((c) => c.label), ["Approvals", "Review"]);
assert.deepEqual(breadcrumb("DEVELOPER", "/knowledge/documents/abc").map((c) => c.label), ["Knowledge", "Document"]);
assert.deepEqual(breadcrumb("OPERATOR", "/agents/abc").map((c) => c.label), ["Agents", "Agent"]);
assert.equal(canAccess("GUEST", "overview.read"), false);

assert.equal(decideRoute("OPERATOR", "/audit").kind, "forbidden");
assert.equal(decideRoute("OPERATOR", "/admin").kind, "forbidden");
assert.equal(decideRoute("DEVELOPER", "/runs/new").kind, "forbidden");
assert.equal(decideRoute("REVIEWER", "/evaluations").kind, "forbidden");
assert.equal(decideRoute("DEVELOPER", "/agents").kind, "allow");
assert.equal(decideRoute("ADMIN", "/approvals").kind, "allow");
assert.equal(decideRoute("OPERATOR", "/runs/abc").kind, "allow");
assert.equal(decideRoute("OPERATOR", "/missing").kind, "unknown");

assert.equal(commandsFor("OPERATOR", "audit").length, 0);
assert.equal(commandsFor("OPERATOR", "support")[0]?.href, "/runs/new");
assert.equal(commandsFor("ADMIN", "audit")[0]?.href, "/audit");
assert.equal(activeItem("ADMIN", "/runs/abc")?.href, "/runs/new");
assert.equal(activeItem("OPERATOR", "/")?.href, "/");
assert.equal(commandsFor("OPERATOR", "refresh")[0]?.href, "/");
assert.equal(environmentLabel("dev"), "LOCAL");
assert.equal(environmentLabel("production"), "PRODUCTION");
assert.notEqual(environmentLabel("dev"), "PRODUCTION");

console.log("access tests passed");
