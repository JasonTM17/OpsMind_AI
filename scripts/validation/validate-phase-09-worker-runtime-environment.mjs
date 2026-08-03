import fs from "node:fs";

import {
  sensitiveWorkerEnvironmentNames,
  validateWorkerEnvironmentNames,
} from "./phase-09-temporal-worker-environment-contract.mjs";

const [environmentPath] = process.argv.slice(2);
if (!environmentPath) {
  throw new Error("Usage: node validate-phase-09-worker-runtime-environment.mjs <names-file>");
}

const names = [...new Set(fs.readFileSync(environmentPath, "utf8")
  .split(/\r?\n/u)
  .map((name) => name.trim())
  .filter(Boolean))];
const applicationNames = names.filter((name) =>
  name.startsWith("OPSMIND_")
  || /^(?:POSTGRES|SPRING|AI|DEEPSEEK|OIDC|TOOL|DATABASE|PG|REDIS|KAFKA|MINIO)_/.test(name),
);
const failures = validateWorkerEnvironmentNames(applicationNames);
const sensitiveNames = sensitiveWorkerEnvironmentNames(names)
  .filter((name) => name !== "GPG_KEY");
for (const name of sensitiveNames) {
  if (!applicationNames.includes(name)) {
    failures.push(`worker runtime contains a sensitive variable: ${name}`);
  }
}

if (failures.length > 0) {
  throw new Error(failures.join("\n"));
}

process.stdout.write(
  `Phase9WorkerRuntimeEnvironment=PASS\nNames=${names.length}\n`,
);
