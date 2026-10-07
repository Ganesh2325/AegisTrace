import assert from "node:assert/strict";
import { isExecutionActive, progressText, rateText } from "./evaluations.ts";

assert.equal(rateText({ value: null, status: "NO_DATA", numerator: 0, denominator: 0, definition: "" }), "No data");
assert.equal(rateText({ value: 0, status: "OK", numerator: 0, denominator: 4, definition: "" }), "0%");
assert.equal(rateText({ value: 0.875, status: "OK", numerator: 7, denominator: 8, definition: "" }), "87.5%");
assert.equal(progressText({ completedCases: 3, totalCases: 8 }), "3 of 8");
assert.equal(isExecutionActive("QUEUED"), true);
assert.equal(isExecutionActive("RUNNING"), true);
assert.equal(isExecutionActive("PARTIAL"), false);

console.log("evaluation frontend tests passed");
