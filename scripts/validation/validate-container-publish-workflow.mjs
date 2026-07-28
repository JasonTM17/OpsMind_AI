import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { parse } from "yaml";

import { validateContainerPublishWorkflow } from "./container-publish-workflow-contract.mjs";
import { parseStrictSemVer } from "./strict-semver.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..", "..");
const workflowPath = path.join(
  repositoryRoot,
  ".github",
  "workflows",
  "container-publish.yml",
);
const source = fs.readFileSync(workflowPath, "utf8");
const document = parse(source);
const errors = validateContainerPublishWorkflow(document);

function clone(value) {
  return structuredClone(value);
}

const mutations = [
  [
    "unpinned action",
    (value) => {
      value.jobs.authorize.steps.push({ uses: "owner/action@main" });
    },
    "action.unpinned:",
  ],
  [
    "extra write permission",
    (value) => {
      value.jobs.authorize.permissions.issues = "write";
    },
    "permissions.authorize",
  ],
  [
    "unapproved pinned action",
    (value) => {
      value.jobs.authorize.steps.push({
        uses: "owner/action@0000000000000000000000000000000000000000",
      });
    },
    "action.unapproved:",
  ],
  [
    "removed protected environment",
    (value) => {
      delete value.jobs.promote.environment;
    },
    "environment.protected-promotion",
  ],
  [
    "job-scoped Docker Hub secret",
    (value) => {
      value.jobs.promote.env = {
        DOCKERHUB_TOKEN: "${{ secrets.DOCKERHUB_TOKEN }}",
      };
    },
    "secret.dockerhub-step-scope",
  ],
  [
    "automatic tag trigger",
    (value) => {
      value.on.push = { tags: ["v*"] };
    },
    "trigger.manual-only",
  ],
  [
    "Docker Hub enabled by default",
    (value) => {
      value.on.workflow_dispatch.inputs.publish_dockerhub.default = true;
    },
    "trigger.fail-closed-inputs",
  ],
  [
    "removed main authorization guard",
    (value) => {
      value.jobs.authorize.if = "true";
    },
    "flow.main-only-authorization",
  ],
  [
    "cancellable overlapping release",
    (value) => {
      value.concurrency["cancel-in-progress"] = true;
    },
    "flow.global-noncancelling-concurrency",
  ],
  [
    "promotion without aggregate gate",
    (value) => {
      value.jobs.promote.needs = "authorize";
    },
    "flow.promote-needs-all-candidates",
  ],
  [
    "mutable tag before signature verification",
    (value) => {
      const step = value.jobs.promote.steps.find(
        (entry) => entry.name === "Promote tested digests",
      );
      step.run += '\noras cp "$source" "${target}:latest"\n';
    },
    "release.public-tag-order",
  ],
];

for (const [name, mutate, expectedErrorPrefix] of mutations) {
  const candidate = clone(document);
  mutate(candidate);
  const mutationErrors = validateContainerPublishWorkflow(candidate);
  if (!mutationErrors.some((error) => error.startsWith(expectedErrorPrefix))) {
    errors.push(`negative mutation escaped validation: ${name}`);
  }
}

for (const value of [
  "0.1.0",
  "1.2.3",
  "1.2.3-rc.1",
  "1.2.3-rc.1+build.7",
]) {
  if (!parseStrictSemVer(value)) errors.push(`valid SemVer rejected: ${value}`);
}
if (parseStrictSemVer("1.2.3+build.7")?.releaseTag !== "1.2.3_build.7") {
  errors.push("SemVer build metadata was not normalized for OCI tags");
}
if (
  parseStrictSemVer("1.2.3-rc.1")?.isPrerelease !== true ||
  parseStrictSemVer("1.2.3")?.isPrerelease !== false
) {
  errors.push("SemVer pre-release channel classification failed");
}
for (const value of [
  "v1.2.3",
  "01.2.3",
  "1.02.3",
  "1.2.03",
  "1.2.3-01",
  "1.2.3..",
  "1.2",
]) {
  if (parseStrictSemVer(value)) errors.push(`invalid SemVer accepted: ${value}`);
}

console.log("OpsMind OCI publication workflow validation");
console.log("EvidenceSchemaVersion=oci-publication-static-v2");
console.log(
  `Workflow=${path.relative(repositoryRoot, workflowPath).replaceAll("\\", "/")}`,
);
console.log("Trigger=MANUAL_MAIN_ONLY");
console.log("CandidateGate=BUILD_SCAN_SMOKE");
console.log("PromotionGate=PROTECTED_AGGREGATE_DIGEST");
console.log("Images=4");
console.log("Platforms=linux/amd64,linux/arm64");
console.log("NegativeMutations=11");
console.log(`Errors=${errors.length}`);
for (const error of errors) console.error(`Error=${error}`);
console.log(`Result=${errors.length === 0 ? "PASS" : "BLOCK"}`);

if (errors.length > 0) process.exit(1);
