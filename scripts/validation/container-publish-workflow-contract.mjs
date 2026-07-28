import { validateReleaseFlow } from "./container-publish-release-flow-contract.mjs";

const approvedActions = new Map([
  ["actions/checkout", "3d3c42e5aac5ba805825da76410c181273ba90b1"],
  ["actions/download-artifact", "3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c"],
  ["actions/upload-artifact", "043fb46d1a93c77aae656e7c1c64a875d1fc6a0a"],
  ["actions/attest", "f7c74d28b9d84cb8768d0b8ca14a4bac6ef463e6"],
  ["aquasecurity/trivy-action", "ed142fd0673e97e23eac54620cfb913e5ce36c25"],
  ["docker/build-push-action", "53b7df96c91f9c12dcc8a07bcb9ccacbed38856a"],
  ["docker/login-action", "abd2ef45e78c5afb21d64d4ca52ee8550d9572c7"],
  ["docker/setup-buildx-action", "bb05f3f5519dd87d3ba754cc423b652a5edd6d2c"],
  ["docker/setup-qemu-action", "96fe6ef7f33517b61c61be40b68a1882f3264fb8"],
]);

const expectedPermissions = {
  authorize: { contents: "read" },
  "build-candidate": {
    contents: "read",
    packages: "write",
    "id-token": "write",
    attestations: "write",
  },
  promote: {
    contents: "read",
    packages: "write",
    "id-token": "write",
    attestations: "write",
  },
};

function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

function collectActionReferences(value, references = []) {
  if (Array.isArray(value)) {
    value.forEach((entry) => collectActionReferences(entry, references));
  } else if (value && typeof value === "object") {
    for (const [key, entry] of Object.entries(value)) {
      if (key === "uses") references.push(entry);
      collectActionReferences(entry, references);
    }
  }
  return references;
}

function collectSecretLocations(value, pathParts = [], locations = []) {
  if (Array.isArray(value)) {
    value.forEach((entry, index) =>
      collectSecretLocations(entry, [...pathParts, index], locations),
    );
  } else if (value && typeof value === "object") {
    for (const [key, entry] of Object.entries(value)) {
      collectSecretLocations(entry, [...pathParts, key], locations);
    }
  } else if (
    typeof value === "string" &&
    value.includes("secrets.DOCKERHUB_TOKEN")
  ) {
    locations.push(pathParts.join("."));
  }
  return locations;
}

function validateStructure(workflow, errors) {
  if (canonical(Object.keys(workflow?.on ?? {})) !== canonical(["workflow_dispatch"])) {
    errors.push("workflow trigger must be workflow_dispatch only");
  }
  const inputs = workflow?.on?.workflow_dispatch?.inputs ?? {};
  if (
    inputs.release_version?.required !== true ||
    inputs.release_version?.type !== "string" ||
    inputs.publish_dockerhub?.required !== true ||
    inputs.publish_dockerhub?.type !== "boolean" ||
    inputs.publish_dockerhub?.default !== false
  ) {
    errors.push("workflow dispatch inputs are not fail-closed");
  }
  if (canonical(workflow.permissions) !== canonical({})) {
    errors.push("top-level permissions must be empty");
  }
  if (
    workflow?.concurrency?.["cancel-in-progress"] !== false ||
    workflow?.concurrency?.group !== "container-publish"
  ) {
    errors.push("publication concurrency must be global and non-cancelling");
  }
}

function validateJobs(workflow, errors) {
  const jobs = workflow.jobs ?? {};
  if (
    canonical(Object.keys(jobs).sort()) !==
    canonical(Object.keys(expectedPermissions).sort())
  ) {
    errors.push("workflow jobs must be authorize, build-candidate, and promote");
  }
  for (const [jobName, permissions] of Object.entries(expectedPermissions)) {
    if (canonical(jobs[jobName]?.permissions) !== canonical(permissions)) {
      errors.push(`${jobName} permissions differ from the exact allowlist`);
    }
  }
  if (
    !jobs.authorize?.if?.includes("refs/heads/main") ||
    !jobs.authorize?.if?.includes("JasonTM17/OpsMind_AI")
  ) {
    errors.push("authorization job must restrict repository and main ref");
  }
  if (jobs.promote?.environment?.name !== "oci-production") {
    errors.push("promotion must use the protected oci-production environment");
  }
  if (
    !Array.isArray(jobs.promote?.needs) ||
    !jobs.promote.needs.includes("authorize") ||
    !jobs.promote.needs.includes("build-candidate")
  ) {
    errors.push("promotion must require authorization and every candidate");
  }

  const matrix = jobs["build-candidate"]?.strategy?.matrix?.include ?? [];
  const imageContracts = matrix.map(({ image, dockerfile }) => `${image}:${dockerfile}`);
  const expectedImages = [
    "opsmind-platform-api:services/platform-api/Dockerfile",
    "opsmind-ai-runtime:services/ai-runtime/Dockerfile",
    "opsmind-tool-gateway:services/tool-gateway/Dockerfile",
    "opsmind-operator-web:apps/operator-web/Dockerfile",
  ];
  if (canonical(imageContracts) !== canonical(expectedImages)) {
    errors.push("candidate matrix does not contain the four exact image contracts");
  }
  if (jobs["build-candidate"]?.strategy?.["fail-fast"] !== false) {
    errors.push("candidate matrix must collect every component result");
  }
  return jobs;
}

function validateActionsAndSecrets(workflow, jobs, errors) {
  for (const reference of collectActionReferences(workflow)) {
    const match = /^([^@\s]+)@([0-9a-f]{40})$/.exec(reference);
    if (!match) {
      errors.push(`action is not pinned to a 40-character SHA: ${reference}`);
    } else if (approvedActions.get(match[1]) !== match[2]) {
      errors.push(`action pin is not approved: ${reference}`);
    }
  }

  const secretLocations = collectSecretLocations(workflow);
  const allowedSecretLocations = [
    "jobs.promote.steps.0.env.DOCKERHUB_TOKEN",
    "jobs.promote.steps.3.with.password",
  ];
  if (
    canonical(secretLocations.sort()) !== canonical(allowedSecretLocations.sort()) ||
    jobs.promote.env ||
    jobs["build-candidate"].env
  ) {
    errors.push("Docker Hub token is not limited to protected promotion steps");
  }
}

export function validateWorkflow(workflow) {
  const errors = [];
  validateStructure(workflow, errors);
  const jobs = validateJobs(workflow, errors);
  validateActionsAndSecrets(workflow, jobs, errors);
  validateReleaseFlow(jobs, errors);
  return errors;
}
