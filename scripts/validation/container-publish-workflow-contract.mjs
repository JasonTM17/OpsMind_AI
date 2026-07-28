import { validateReleaseFlow } from "./container-publish-release-flow-contract.mjs";

const expectedImages = new Map([
  ["opsmind-platform-api", "services/platform-api/Dockerfile"],
  ["opsmind-ai-runtime", "services/ai-runtime/Dockerfile"],
  ["opsmind-tool-gateway", "services/tool-gateway/Dockerfile"],
  ["opsmind-operator-web", "apps/operator-web/Dockerfile"],
]);

const expectedPermissions = {
  authorize: { actions: "read", contents: "read" },
  "build-candidate": { contents: "read", packages: "write" },
  promote: {
    attestations: "write",
    contents: "read",
    "id-token": "write",
    packages: "write",
  },
};

const expectedActions = new Map([
  ["actions/attest", "f7c74d28b9d84cb8768d0b8ca14a4bac6ef463e6"],
  ["actions/checkout", "3d3c42e5aac5ba805825da76410c181273ba90b1"],
  [
    "actions/download-artifact",
    "3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c",
  ],
  ["actions/upload-artifact", "043fb46d1a93c77aae656e7c1c64a875d1fc6a0a"],
  ["aquasecurity/trivy-action", "ed142fd0673e97e23eac54620cfb913e5ce36c25"],
  ["docker/build-push-action", "53b7df96c91f9c12dcc8a07bcb9ccacbed38856a"],
  ["docker/login-action", "abd2ef45e78c5afb21d64d4ca52ee8550d9572c7"],
  ["docker/metadata-action", "dc802804100637a589fabce1cb79ff13a1411302"],
  ["docker/setup-buildx-action", "bb05f3f5519dd87d3ba754cc423b652a5edd6d2c"],
  ["docker/setup-qemu-action", "96fe6ef7f33517b61c61be40b68a1882f3264fb8"],
  ["oras-project/setup-oras", "1d808f7d7f6995cc68b7bf507bfe5c5446e1dc9d"],
]);

function sameRecord(actual, expected) {
  if (!actual || typeof actual !== "object" || Array.isArray(actual)) return false;
  const actualEntries = Object.entries(actual).sort();
  const expectedEntries = Object.entries(expected).sort();
  return JSON.stringify(actualEntries) === JSON.stringify(expectedEntries);
}

function visit(node, callback, trail = []) {
  if (!node || typeof node !== "object") return;
  callback(node, trail);
  for (const [key, value] of Object.entries(node)) {
    visit(value, callback, [...trail, key]);
  }
}

function stepByName(job, name) {
  return job?.steps?.find((step) => step.name === name);
}

export function validateContainerPublishWorkflow(document) {
  const errors = [];
  const trigger = document?.on;
  const jobs = document?.jobs ?? {};

  if (!sameRecord(document?.permissions, {})) {
    errors.push("permissions.top-level-deny");
  }
  if (
    !trigger ||
    !sameRecord(Object.fromEntries(Object.keys(trigger).map((key) => [key, true])), {
      workflow_dispatch: true,
    })
  ) {
    errors.push("trigger.manual-only");
  }
  const inputs = trigger?.workflow_dispatch?.inputs ?? {};
  if (
    inputs.release_version?.required !== true ||
    inputs.release_version?.type !== "string" ||
    inputs.publish_dockerhub?.required !== true ||
    inputs.publish_dockerhub?.type !== "boolean" ||
    inputs.publish_dockerhub?.default !== false
  ) {
    errors.push("trigger.fail-closed-inputs");
  }
  if (
    document?.concurrency?.group !== "oci-production-publication" ||
    document?.concurrency?.["cancel-in-progress"] !== false
  ) {
    errors.push("flow.global-noncancelling-concurrency");
  }
  if (
    Object.keys(jobs).sort().join(",") !==
    ["authorize", "build-candidate", "promote"].sort().join(",")
  ) {
    errors.push("flow.exact-jobs");
  }
  if (
    !jobs.authorize?.if?.includes("JasonTM17/OpsMind_AI") ||
    !jobs.authorize?.if?.includes("refs/heads/main") ||
    !jobs.authorize?.if?.includes("workflow_dispatch")
  ) {
    errors.push("flow.main-only-authorization");
  }

  for (const [jobName, permissions] of Object.entries(expectedPermissions)) {
    if (!sameRecord(jobs[jobName]?.permissions, permissions)) {
      errors.push(`permissions.${jobName}`);
    }
  }

  if (jobs["build-candidate"]?.needs !== "authorize") {
    errors.push("flow.candidate-needs-authorize");
  }
  if (
    !Array.isArray(jobs.promote?.needs) ||
    jobs.promote.needs.length !== 2 ||
    !jobs.promote.needs.includes("authorize") ||
    !jobs.promote.needs.includes("build-candidate")
  ) {
    errors.push("flow.promote-needs-all-candidates");
  }
  if (jobs.promote?.environment?.name !== "oci-production") {
    errors.push("environment.protected-promotion");
  }

  const matrix = jobs["build-candidate"]?.strategy?.matrix?.include ?? [];
  if (jobs["build-candidate"]?.strategy?.["fail-fast"] !== false) {
    errors.push("flow.collect-all-candidates");
  }
  const actualImages = new Map(
    matrix.map((item) => [item.image, item.dockerfile]),
  );
  if (
    actualImages.size !== expectedImages.size ||
    [...expectedImages].some(
      ([image, dockerfile]) => actualImages.get(image) !== dockerfile,
    )
  ) {
    errors.push("matrix.four-production-images");
  }

  const uses = [];
  visit(document, (node) => {
    if (typeof node.uses === "string") uses.push(node.uses);
  });
  for (const action of uses) {
    if (!/^[^@\s]+@[0-9a-f]{40}$/.test(action)) {
      errors.push(`action.unpinned:${action}`);
      continue;
    }
    const [name, sha] = action.split("@");
    if (expectedActions.get(name) !== sha) {
      errors.push(`action.unapproved:${action}`);
    }
  }
  for (const [name, sha] of expectedActions) {
    if (!uses.includes(`${name}@${sha}`)) {
      errors.push(`action.missing:${name}`);
    }
  }

  validateReleaseFlow(jobs, errors);

  const dockerHubTokenOwners = [];
  visit(document, (node, trail) => {
    for (const value of Object.values(node)) {
      if (
        typeof value === "string" &&
        value.includes("secrets.DOCKERHUB_TOKEN")
      ) {
        const stepsPosition = trail.indexOf("steps");
        const stepIndex = Number(trail[stepsPosition + 1]);
        const jobName = trail[1];
        const stepName = document.jobs?.[jobName]?.steps?.[stepIndex]?.name;
        dockerHubTokenOwners.push(`${jobName}:${stepName ?? "outside-step"}`);
      }
    }
  });
  const allowedSecretOwners = new Set([
    "promote:Validate Docker Hub credentials",
    "promote:Log in to Docker Hub",
  ]);
  if (
    dockerHubTokenOwners.length !== 2 ||
    dockerHubTokenOwners.some(
      (owner) => !allowedSecretOwners.has(owner),
    ) ||
    jobs.promote?.env ||
    jobs["build-candidate"]?.env
  ) {
    errors.push("secret.dockerhub-step-scope");
  }

  return [...new Set(errors)];
}

export const workflowContract = {
  expectedActions,
  expectedImages,
  expectedPermissions,
};
