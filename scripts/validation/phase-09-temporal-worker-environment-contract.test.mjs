import test from "node:test";
import assert from "node:assert/strict";

import {
  sensitiveWorkerEnvironmentNames,
  validateWorkerEnvironmentNames,
  workerEnvironmentAllowlist,
} from "./phase-09-temporal-worker-environment-contract.mjs";

test("worker allowlist accepts only the exact Temporal process contract", () => {
  assert.deepEqual(
    validateWorkerEnvironmentNames([...workerEnvironmentAllowlist]),
    [],
  );
});

test("worker allowlist rejects database, AI, tool, observer, and datasource secrets", () => {
  for (const forbidden of [
    "SPRING_DATASOURCE_PASSWORD",
    "POSTGRES_PASSWORD",
    "DEEPSEEK_API_KEY",
    "OPSMIND_AI_CAPABILITY_PRIVATE_KEY_FILE",
    "OPSMIND_TOOL_CAPABILITY_PRIVATE_KEY_PATH",
    "OPSMIND_INVESTIGATION_TEMPORAL_OBSERVER_API_KEY",
  ]) {
    assert.ok(
      validateWorkerEnvironmentNames([...workerEnvironmentAllowlist, forbidden])
        .some((failure) => failure.includes(forbidden)),
    );
    assert.deepEqual(sensitiveWorkerEnvironmentNames([forbidden]), [forbidden]);
  }
});
