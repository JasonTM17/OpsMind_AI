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
    "mutable component tag during staging",
    (value) => {
      const step = value.jobs.promote.steps.find(
        (entry) => entry.name === "Promote tested digests",
      );
      step.run += '\noras cp "$source" "${target}:latest"\n';
    },
    "release.public-tag-order",
  ],
  [
    "release marker before aggregate verification",
    (value) => {
      const steps = value.jobs.promote.steps;
      const markerIndex = steps.findIndex(
        (entry) => entry.name === "Publish atomic GitHub release marker",
      );
      const [marker] = steps.splice(markerIndex, 1);
      const verifyIndex = steps.findIndex(
        (entry) => entry.name === "Verify staged immutable release set",
      );
      steps.splice(verifyIndex, 0, marker);
    },
    "release.step-order",
  ],
  [
    "immutable release preflight removed",
    (value) => {
      const step = value.jobs.promote.steps.find(
        (entry) => entry.name === "Validate aggregate candidate release set",
      );
      step.run = step.run.replace(".enabled == true", ".enabled != false");
    },
    "release.immutability-preflight",
  ],
  [
    "component attestation storage record enabled for user-owned package",
    (value) => {
      const step = value.jobs.promote.steps.find(
        (entry) => entry.name === "Attest Platform API on GHCR",
      );
      step.with["create-storage-record"] = true;
    },
    "release.component-attestation-config",
  ],
  [
    "registry credential cleanup loses failure path",
    (value) => {
      const step = value.jobs.promote.steps.find(
        (entry) => entry.name === "Close registry credential session",
      );
      step.if = "success()";
    },
    "release.credential-lifecycle",
  ],
  [
    "dual-registry requirement removed",
    (value) => {
      const step = value.jobs.authorize.steps.find(
        (entry) => entry.name === "Require atomic dual-registry release",
      );
      step.run = step.run.replace(
        'if [[ "$PUBLISH_DOCKERHUB" != "true" ]]; then',
        "if false; then",
      );
    },
    "release.dual-registry-required",
  ],
  [
    "registry OCI bundle requirement removed",
    (value) => {
      const step = value.jobs.promote.steps.find(
        (entry) => entry.name === "Verify staged immutable release set",
      );
      step.run = step.run.replaceAll("--bundle-from-oci", "");
    },
    "release.registry-attestation-policy",
  ],
  [
    "anonymous registry Docker config removed",
    (value) => {
      const step = value.jobs.promote.steps.find(
        (entry) => entry.name === "Verify staged immutable release set",
      );
      step.run = step.run.replace(
        'export DOCKER_CONFIG="$anonymous_config"',
        "",
      );
    },
    "release.anonymous-registry-verification",
  ],
  [
    "anonymous registry config file removed",
    (value) => {
      const step = value.jobs.promote.steps.find(
        (entry) => entry.name === "Verify staged immutable release set",
      );
      step.run = step.run.replace(
        `printf '%s\\n' '{}' > "$anonymous_config/config.json"`,
        "",
      );
    },
    "release.anonymous-registry-verification",
  ],
  [
    "Docker Hub receipt parity removed",
    (value) => {
      const step = value.jobs.promote.steps.find(
        (entry) => entry.name === "Write observed immutable release receipt",
      );
      step.run = step.run.replace(
        ".dockerHub.digest == .digest",
        "",
      );
    },
    "release.dual-registry-receipt",
  ],
  [
    "aggregate signer policy removed",
    (value) => {
      const step = value.jobs.promote.steps.find(
        (entry) => entry.name === "Verify aggregate release evidence attestation",
      );
      step.run = step.run.replace(
        '--signer-workflow "$SIGNER_WORKFLOW"',
        "",
      );
    },
    "release.aggregate-attestation-policy",
  ],
  [
    "signer workflow identity drifted",
    (value) => {
      const signerWorkflow =
        "${{ github.repository }}/.github/workflows/alternate.yml";
      const stageStep = value.jobs.promote.steps.find(
        (entry) => entry.name === "Verify staged immutable release set",
      );
      const receiptStep = value.jobs.promote.steps.find(
        (entry) => entry.name === "Write observed immutable release receipt",
      );
      const aggregateStep = value.jobs.promote.steps.find(
        (entry) =>
          entry.name === "Verify aggregate release evidence attestation",
      );
      stageStep.env.SIGNER_WORKFLOW = signerWorkflow;
      aggregateStep.env.SIGNER_WORKFLOW = signerWorkflow;
      receiptStep.run = receiptStep.run.replace(
        '--arg signerWorkflow "$GITHUB_REPOSITORY/.github/workflows/container-publish.yml"',
        '--arg signerWorkflow "$GITHUB_REPOSITORY/.github/workflows/alternate.yml"',
      );
    },
    "release.signer-workflow-binding",
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
console.log("EvidenceSchemaVersion=oci-publication-static-v4");
console.log(
  `Workflow=${path.relative(repositoryRoot, workflowPath).replaceAll("\\", "/")}`,
);
console.log("Trigger=MANUAL_MAIN_ONLY");
console.log("CandidateGate=BUILD_SCAN_SMOKE");
console.log("PromotionGate=SIGNED_IMMUTABLE_RELEASE_SET");
console.log("Images=4");
console.log("Platforms=linux/amd64,linux/arm64");
console.log(`NegativeMutations=${mutations.length}`);
console.log(`Errors=${errors.length}`);
for (const error of errors) console.error(`Error=${error}`);
console.log(`Result=${errors.length === 0 ? "PASS" : "BLOCK"}`);

if (errors.length > 0) process.exit(1);
