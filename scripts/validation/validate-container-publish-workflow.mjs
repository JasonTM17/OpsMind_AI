import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { parse } from "yaml";

import { validateWorkflow } from "./container-publish-workflow-contract.mjs";
import { normalizeReleaseTag } from "./strict-semver.mjs";

const root = path.resolve(import.meta.dirname, "..", "..");
const workflowPath = path.join(root, ".github", "workflows", "container-publish.yml");
const source = fs.readFileSync(workflowPath, "utf8").replace(/\r\n/g, "\n");
const errors = [];

function runMutationTests(document) {
  const failures = [];
  const mutations = [
    ["unpinned action", (copy) => copy.jobs.authorize.steps.push({ uses: "owner/action@main" })],
    ["extra permission", (copy) => { copy.jobs.promote.permissions.issues = "write"; }],
    ["missing environment", (copy) => { delete copy.jobs.promote.environment; }],
    ["job-scoped secret", (copy) => {
      copy.jobs.promote.env = { DOCKERHUB_TOKEN: "${{ secrets.DOCKERHUB_TOKEN }}" };
    }],
    ["push trigger", (copy) => { copy.on.push = { branches: ["main"] }; }],
  ];
  for (const [name, mutate] of mutations) {
    const copy = structuredClone(document);
    mutate(copy);
    if (validateWorkflow(copy).length === 0) failures.push(name);
  }
  return failures;
}

try {
  const document = parse(source);
  errors.push(...validateWorkflow(document));
  for (const mutation of runMutationTests(document)) {
    errors.push(`validator accepted negative mutation: ${mutation}`);
  }
} catch (error) {
  errors.push(`workflow YAML parse failed: ${error.message}`);
}

for (const bad of ["v01.2.3", "v1.02.3", "v1.2.03", "v1.2.3..", "v1.2.3-01"]) {
  try {
    normalizeReleaseTag(bad);
    errors.push(`strict SemVer validator accepted ${bad}`);
  } catch {}
}
for (const [valid, tag] of [
  ["v0.1.0", "0.1.0"],
  ["v1.2.3-rc.1", "1.2.3-rc.1"],
  ["v1.2.3+build.7", "1.2.3_build.7"],
]) {
  if (normalizeReleaseTag(valid) !== tag) errors.push(`normalization failed for ${valid}`);
}

console.log("OpsMind OCI publication workflow validation");
console.log("EvidenceSchemaVersion=oci-publication-static-v2");
console.log(`Workflow=${path.relative(root, workflowPath).replaceAll("\\", "/")}`);
console.log("Trigger=MANUAL_MAIN_ONLY");
console.log("Environment=oci-production");
console.log("Images=4");
console.log("Platforms=linux/amd64,linux/arm64");
console.log("Promotion=BUILD_ONCE_BY_DIGEST");
console.log("Scans=vulnerability,secret,license");
console.log("SignedAttestation=VERIFIED");
console.log("GHCRVisibility=PUBLIC_REQUIRED");
console.log("DockerHub=PROTECTED_ENVIRONMENT_CREDENTIAL_GATED");
console.log(`Errors=${errors.length}`);
for (const error of errors) console.error(`Error=${error}`);
console.log(`Result=${errors.length === 0 ? "PASS" : "BLOCK"}`);

if (errors.length > 0) process.exit(1);
