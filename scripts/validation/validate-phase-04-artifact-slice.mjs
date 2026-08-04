import { spawnSync } from "node:child_process";

const result = spawnSync(
  process.execPath,
  ["scripts/validation/validate-phase-04c-evidence-artifacts.mjs"],
  { stdio: "inherit" },
);
process.exit(result.status ?? 1);
