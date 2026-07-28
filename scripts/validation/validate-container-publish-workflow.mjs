import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const repositoryRoot = path.resolve(import.meta.dirname, "..", "..");
const workflowPath = path.join(
  repositoryRoot,
  ".github",
  "workflows",
  "container-publish.yml",
);
const source = fs.readFileSync(workflowPath, "utf8").replace(/\r\n/g, "\n");
const errors = [];

function requireText(value, description) {
  if (!source.includes(value)) errors.push(`missing ${description}`);
}

function requirePattern(pattern, description) {
  if (!pattern.test(source)) errors.push(`missing ${description}`);
}

for (const [value, description] of [
  ["workflow_dispatch:", "manual publication trigger"],
  ['- "v*"', "release-tag publication trigger"],
  ["contents: read", "read-only source permission"],
  ["packages: write", "package publication permission"],
  ["id-token: write", "keyless signing permission"],
  ["attestations: write", "attestation publication permission"],
  ["cancel-in-progress: false", "non-cancelling publication concurrency"],
  ["github.ref == 'refs/heads/main'", "main-only manual publication guard"],
  ["fetch-depth: 0", "release ancestry history"],
  ["git merge-base --is-ancestor", "release tag main-ancestry check"],
  ["Release tag must use a vMAJOR.MINOR.PATCH", "release tag SemVer check"],
  ["linux/amd64,linux/arm64", "multi-architecture target"],
  ["provenance: mode=max", "maximum provenance"],
  ["sbom: true", "SBOM generation"],
  ["push: true", "registry publication"],
  ["push-to-registry: true", "signed registry attestation"],
  ["DOCKERHUB_USERNAME", "Docker Hub namespace variable"],
  ["DOCKERHUB_TOKEN", "Docker Hub protected token"],
  ["dockerhub_enabled", "explicit Docker Hub publication receipt"],
  ["org.opencontainers.image.source=", "repository linkage label"],
  ["org.opencontainers.image.revision=", "revision label"],
  ["opsmind-oci-publication-v1", "versioned publication receipt"],
]) {
  requireText(value, description);
}

for (const [image, dockerfile] of [
  ["opsmind-platform-api", "services/platform-api/Dockerfile"],
  ["opsmind-ai-runtime", "services/ai-runtime/Dockerfile"],
  ["opsmind-tool-gateway", "services/tool-gateway/Dockerfile"],
  ["opsmind-operator-web", "apps/operator-web/Dockerfile"],
]) {
  requireText(`image: ${image}`, `${image} image`);
  requireText(`dockerfile: ${dockerfile}`, `${image} Dockerfile`);
}

for (const [action, sha] of [
  ["actions/checkout", "3d3c42e5aac5ba805825da76410c181273ba90b1"],
  ["docker/login-action", "abd2ef45e78c5afb21d64d4ca52ee8550d9572c7"],
  ["docker/setup-qemu-action", "96fe6ef7f33517b61c61be40b68a1882f3264fb8"],
  ["docker/setup-buildx-action", "bb05f3f5519dd87d3ba754cc423b652a5edd6d2c"],
  ["docker/metadata-action", "dc802804100637a589fabce1cb79ff13a1411302"],
  ["docker/build-push-action", "53b7df96c91f9c12dcc8a07bcb9ccacbed38856a"],
  ["actions/attest", "f7c74d28b9d84cb8768d0b8ca14a4bac6ef463e6"],
  ["actions/upload-artifact", "043fb46d1a93c77aae656e7c1c64a875d1fc6a0a"],
]) {
  requireText(`uses: ${action}@${sha}`, `${action} immutable pin`);
}

requirePattern(
  /if: steps\.targets\.outputs\.dockerhub_enabled == 'true'\n\s+uses: docker\/login-action@/,
  "Docker Hub login condition",
);
requirePattern(
  /if \[\[ -z "\$DOCKERHUB_USERNAME" \|\| -z "\$DOCKERHUB_TOKEN" \]\]; then/,
  "fail-closed Docker Hub credential check",
);
requirePattern(
  /docker buildx imagetools inspect "\$\{GHCR_IMAGE\}@\$\{IMAGE_DIGEST\}"/,
  "GHCR digest verification",
);

if (/pull_request_target\s*:/.test(source)) {
  errors.push("pull_request_target must not publish packages");
}
if (/uses:\s+[^@\s]+@v\d/.test(source)) {
  errors.push("mutable major-version action reference is forbidden");
}
if (/password:\s+(?!\$\{\{\s*(?:secrets|env)\.)\S+/.test(source)) {
  errors.push("literal registry password is forbidden");
}

console.log("OpsMind OCI publication workflow validation");
console.log("EvidenceSchemaVersion=oci-publication-static-v1");
console.log(`Workflow=${path.relative(repositoryRoot, workflowPath).replaceAll("\\", "/")}`);
console.log("Images=4");
console.log("Platforms=linux/amd64,linux/arm64");
console.log("SBOM=REQUIRED");
console.log("Provenance=mode=max");
console.log("SignedAttestation=REQUIRED");
console.log("GHCR=REQUIRED");
console.log("DockerHub=PROTECTED_CREDENTIAL_GATED");
console.log(`Errors=${errors.length}`);
for (const error of errors) console.error(`Error=${error}`);
console.log(`Result=${errors.length === 0 ? "PASS" : "BLOCK"}`);

if (errors.length > 0) process.exit(1);
