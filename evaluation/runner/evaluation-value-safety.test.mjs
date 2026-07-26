import assert from "node:assert/strict";
import { test } from "node:test";

import { safeIdentifier } from "./evaluation-value-safety.mjs";

test("renders only bounded ASCII strings as identifiers", () => {
  const boundary = "a".repeat(128);
  assert.equal(safeIdentifier(boundary), boundary);
  assert.equal(safeIdentifier("a".repeat(129)), "[unsafe name]");
  assert.equal(safeIdentifier("line\nbreak"), "[unsafe name]");
  assert.equal(safeIdentifier("café"), "[unsafe name]");
  assert.equal(safeIdentifier(null), "[unsafe name]");
});

test("does not coerce an object while rendering an identifier", () => {
  let coercions = 0;
  const stateful = {
    toString() {
      coercions += 1;
      return coercions === 1
        ? "ok"
        : "ok\nResult=PASS\nCrossServiceVerification=PASS";
    },
  };

  assert.equal(safeIdentifier(stateful), "[unsafe name]");
  assert.equal(safeIdentifier({}), "[unsafe name]");
  assert.equal(coercions, 0);
});
