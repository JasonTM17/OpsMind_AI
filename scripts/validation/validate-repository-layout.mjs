import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  prepareValidationEvidence,
  publishValidationEvidence,
} from "./safe-validation-evidence.mjs";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "../..");
const ignoredDirectories = new Set([
  ".agents",
  ".claude",
  ".codex",
  ".git",
  ".next",
  ".opsmind",
  "artifacts",
  "node_modules",
  "target",
]);
const expectedFiles = [
  ".dockerignore",
  ".editorconfig",
  ".env.example",
  ".github/workflows/pr-quality.yml",
  ".github/actions/install-pinned-maven/action.yml",
  ".gitignore",
  ".java-version",
  ".maven-version",
  ".node-version",
  ".python-version",
  "Makefile",
  "compose.yaml",
  "package.json",
  "pnpm-lock.yaml",
  "pnpm-workspace.yaml",
  "scripts/dev/install-pinned-actionlint.mjs",
  "scripts/dev/install-pinned-osv-scanner.mjs",
  "scripts/dev/install-pinned-osv-scanner.test.mjs",
  "scripts/dev/install-pinned-maven.ps1",
  "scripts/dev/recover-stale-command-lock.mjs",
  "scripts/dev/opsmind.ps1",
  "scripts/dev/opsmind.sh",
  "scripts/security/evaluate-osv-results.mjs",
  "scripts/security/evaluate-osv-results.test.mjs",
  "scripts/validation/validate-phase-03-trust-foundation.mjs",
  "scripts/validation/run-phase-03-postgres-contract.sh",
  "apps/operator-web/Dockerfile",
  "apps/operator-web/package.json",
  "packages/contracts/openapi/opsmind-v1.yaml",
  "services/ai-runtime/Dockerfile",
  "services/ai-runtime/pyproject.toml",
  "services/ai-runtime/uv.lock",
  "services/platform-api/Dockerfile",
  "services/platform-api/src/main/resources/db/bootstrap/001-create-runtime-role.sh",
  "services/platform-api/pom.xml",
  "services/tool-gateway/Dockerfile",
  "services/tool-gateway/pom.xml",
];
const expectedCommands = [
  "setup",
  "dev",
  "test",
  "lint",
  "build",
  "up",
  "down",
  "migrate",
  "seed",
  "evaluate",
  "security",
  "security-scan",
];
const errors = [];

function relative(filePath) {
  return path.relative(repositoryRoot, filePath).split(path.sep).join("/");
}

function walk(directory) {
  const files = [];
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (entry.isDirectory() && ignoredDirectories.has(entry.name)) continue;
    const entryPath = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) continue;
    if (entry.isDirectory()) files.push(...walk(entryPath));
    else if (entry.isFile()) files.push(entryPath);
  }
  return files;
}

for (const file of expectedFiles) {
  if (!fs.existsSync(path.join(repositoryRoot, file))) errors.push(`missing required file: ${file}`);
}
if (fs.existsSync(path.join(repositoryRoot, "apps/web"))) {
  errors.push("alternate frontend root is forbidden: apps/web");
}

const rootComposeFiles = fs
  .readdirSync(repositoryRoot, { withFileTypes: true })
  .filter((entry) => entry.isFile() && /^compose(?:[.-].*)?\.ya?ml$/i.test(entry.name))
  .map((entry) => entry.name);
if (rootComposeFiles.length !== 1 || rootComposeFiles[0] !== "compose.yaml") {
  errors.push(`expected only compose.yaml at root; found: ${rootComposeFiles.join(",") || "none"}`);
}
if (rootComposeFiles[0] === "compose.yaml") {
  const compose = fs.readFileSync(path.join(repositoryRoot, "compose.yaml"), "utf8");
  const publishedPorts = [...compose.matchAll(/^\s*-\s*"([^"\r\n]+:\d+)"\s*$/gm)].map(
    (match) => match[1],
  );
  if (publishedPorts.length !== 8 || publishedPorts.some((port) => !port.startsWith("127.0.0.1:"))) {
    errors.push("all eight local Compose port bindings must be explicitly loopback-only");
  }
  const activeImages = [...compose.matchAll(/^\s*image:\s*([^\r\n]+)$/gm)]
    .map((match) => match[1].trim())
    .filter((image) => !image.includes("registry.invalid.example/"));
  if (activeImages.some((image) => !/@sha256:[0-9a-f]{64}$/.test(image))) {
    errors.push("active Compose images must use immutable sha256 digests");
  }
}

const productFiles = walk(repositoryRoot);
const openApiFiles = productFiles.filter((file) => {
  if (!/\.ya?ml$/i.test(file)) return false;
  return /^\s*openapi\s*:/m.test(fs.readFileSync(file, "utf8"));
});
if (openApiFiles.length !== 1 || relative(openApiFiles[0]) !== "packages/contracts/openapi/opsmind-v1.yaml") {
  errors.push(`canonical OpenAPI root violation: ${openApiFiles.map(relative).join(",") || "none"}`);
}

for (const file of productFiles) {
  const filePath = relative(file);
  if (/(^|\/)openapi\//.test(filePath) && !filePath.startsWith("packages/contracts/openapi/")) {
    errors.push(`alternate OpenAPI directory: ${filePath}`);
  }
  if (/(^|\/)json-schema\//.test(filePath) && !filePath.startsWith("packages/contracts/json-schema/")) {
    errors.push(`alternate JSON Schema directory: ${filePath}`);
  }
}

if (fs.existsSync(path.join(repositoryRoot, "package.json"))) {
  const rootManifest = JSON.parse(fs.readFileSync(path.join(repositoryRoot, "package.json"), "utf8"));
  if (!/^pnpm@\d+\.\d+\.\d+$/.test(rootManifest.packageManager ?? "")) {
    errors.push("root packageManager must pin an exact pnpm version");
  }
  for (const command of expectedCommands) {
    if (typeof rootManifest.scripts?.[command] !== "string") {
      errors.push(`root command is missing: ${command}`);
    }
  }
}

const pnpmWorkspacePath = path.join(repositoryRoot, "pnpm-workspace.yaml");
if (fs.existsSync(pnpmWorkspacePath)) {
  const workspace = fs.readFileSync(pnpmWorkspacePath, "utf8");
  for (const policy of [
    "postcss: 8.5.10",
    "'sharp@0.35.3': true",
    "'unrs-resolver@1.12.2': true",
    "strictDepBuilds: true",
    "verifyDepsBeforeRun: error",
    "enableGlobalVirtualStore: false",
  ]) {
    if (!workspace.includes(policy)) errors.push(`pnpm supply-chain policy is missing: ${policy}`);
  }
  if (/set this to true or false|onlyBuiltDependencies:/i.test(workspace)) {
    errors.push("pnpm workspace contains an unresolved or obsolete build-script policy");
  }
}

const pythonManifestPath = path.join(repositoryRoot, "services/ai-runtime/pyproject.toml");
if (fs.existsSync(pythonManifestPath)) {
  const pythonManifest = fs.readFileSync(pythonManifestPath, "utf8");
  if (!/required-version\s*=\s*"==0\.11\.29"/.test(pythonManifest)) {
    errors.push("AI Runtime must pin uv 0.11.29 in pyproject.toml");
  }
  if (!pythonManifest.includes('"httpx2==2.7.0"')) {
    errors.push("AI Runtime tests must use the supported httpx2 2.7.0 client");
  }
}

for (const javaManifest of ["services/platform-api/pom.xml", "services/tool-gateway/pom.xml"]) {
  const javaManifestPath = path.join(repositoryRoot, javaManifest);
  if (!fs.existsSync(javaManifestPath)) continue;
  const manifest = fs.readFileSync(javaManifestPath, "utf8");
  for (const dependencyFloor of [
    "<jackson-bom.version>3.1.5</jackson-bom.version>",
    "<log4j2.version>2.25.5</log4j2.version>",
    "<tomcat.version>11.0.24</tomcat.version>",
  ]) {
    if (!manifest.includes(dependencyFloor)) {
      errors.push(`Java patched dependency floor is missing from ${javaManifest}: ${dependencyFloor}`);
    }
  }
}

const javaContainerContracts = [
  {
    dockerfile: "services/platform-api/Dockerfile",
    manifest: "services/platform-api/pom.xml",
    finalName: "platform-api",
  },
  {
    dockerfile: "services/tool-gateway/Dockerfile",
    manifest: "services/tool-gateway/pom.xml",
    finalName: "tool-gateway",
  },
];

for (const dockerfile of [
  "apps/operator-web/Dockerfile",
  "services/ai-runtime/Dockerfile",
  "services/platform-api/Dockerfile",
  "services/tool-gateway/Dockerfile",
]) {
  if (!fs.existsSync(path.join(repositoryRoot, dockerfile))) continue;
  const contents = fs.readFileSync(path.join(repositoryRoot, dockerfile), "utf8");
  for (const line of contents.split(/\r?\n/).filter((entry) => entry.startsWith("FROM "))) {
    const imageReference = line.replace(/^FROM\s+/i, "").split(/\s+/)[0];
    if (!imageReference.includes("/") && !imageReference.includes(":")) continue;
    if (!/@sha256:[0-9a-f]{64}(?:\s+AS\s+|\s*$)/i.test(line)) {
      errors.push(`Docker base image must use an immutable digest: ${dockerfile}`);
    }
  }
}

for (const contract of javaContainerContracts) {
  const manifest = fs.readFileSync(path.join(repositoryRoot, contract.manifest), "utf8");
  const dockerfile = fs.readFileSync(path.join(repositoryRoot, contract.dockerfile), "utf8");
  if (!manifest.includes(`<finalName>${contract.finalName}</finalName>`)) {
    errors.push(`stable Java artifact name is missing from ${contract.manifest}`);
  }
  if (!dockerfile.includes(`/target/${contract.finalName}.jar app.jar`) || /target\/[^\s]*\*\.jar/.test(dockerfile)) {
    errors.push(`Java Dockerfile must copy only the executable JAR: ${contract.dockerfile}`);
  }
}

const workflowPath = path.join(repositoryRoot, ".github/workflows/pr-quality.yml");
if (fs.existsSync(workflowPath)) {
  const workflow = fs.readFileSync(workflowPath, "utf8");
  for (const contract of [
    "--frozen-lockfile",
    "install-pinned-actionlint.mjs",
    "install-pinned-maven",
    "services/ai-runtime/uv.lock",
    "uv==0.11.29",
    "--locked",
    "sh scripts/dev/opsmind.sh setup",
    ".\\scripts\\dev\\opsmind.ps1 setup",
    "os: [ubuntu-latest, windows-latest]",
    'OPS_MIN_D_FREE_GB: "5"',
    "pnpm audit --audit-level moderate",
    "scan-project-secrets.ps1",
    "Verify Docker storage attestation",
    "docker info --format '{{.DockerRootDir}}'",
    "sh scripts/storage/check-capacity.sh",
    "fail-on-severity: moderate",
  ]) {
    if (!workflow.includes(contract)) errors.push(`CI reproducibility contract is missing: ${contract}`);
  }
}

for (const commandScript of ["scripts/dev/opsmind.ps1", "scripts/dev/opsmind.sh"]) {
  const commandScriptPath = path.join(repositoryRoot, commandScript);
  if (!fs.existsSync(commandScriptPath)) continue;
  const commandSurface = fs.readFileSync(commandScriptPath, "utf8");
  for (const policy of [
    "install-pinned-actionlint.mjs",
    "--config.ci=true",
    "--audit-level",
    "moderate",
    "-DdataDirectory=",
    "-DfailBuildOnCVSS=7",
    "-DfailOnError=true",
    "-Dformat=JSON",
  ]) {
    if (!commandSurface.includes(policy)) {
      errors.push(`security command contract is missing from ${commandScript}: ${policy}`);
    }
  }
}

const portableCommandPath = path.join(repositoryRoot, "scripts/dev/opsmind.sh");
if (fs.existsSync(portableCommandPath)) {
  const portableCommand = fs.readFileSync(portableCommandPath, "utf8");
  if (!/test\)\s*\n\s*require_command pwsh/.test(portableCommand)) {
    errors.push("portable test command must fail explicitly when pwsh is unavailable");
  }
  if (/test-portable-storage-guards\.sh; fi/.test(portableCommand)) {
    errors.push("portable test command must not silently weaken the governance suite");
  }
}

const actionlintInstallerPath = path.join(repositoryRoot, "scripts/dev/install-pinned-actionlint.mjs");
if (fs.existsSync(actionlintInstallerPath)) {
  const installer = fs.readFileSync(actionlintInstallerPath, "utf8");
  for (const contract of ["sourceArchiveName", "verifyCachedInstallation", "assertNoSymbolicLinkAncestors"]) {
    if (!installer.includes(contract)) errors.push(`actionlint cache-integrity contract is missing: ${contract}`);
  }
}

const aiRuntimeDockerfile = path.join(repositoryRoot, "services/ai-runtime/Dockerfile");
if (fs.existsSync(aiRuntimeDockerfile)) {
  const dockerfile = fs.readFileSync(aiRuntimeDockerfile, "utf8");
  if (!dockerfile.includes("ghcr.io/astral-sh/uv:0.11.29") || !dockerfile.includes("uv sync --locked")) {
    errors.push("AI Runtime Dockerfile must use the pinned uv image and locked sync");
  }
  if (!dockerfile.includes("WORKDIR /app") || !dockerfile.includes("COPY --from=build --chown=opsmind:opsmind /app/.venv /app/.venv")) {
    errors.push("AI Runtime Dockerfile must preserve the virtual-environment path across stages");
  }
}

const operatorWebConfigPath = path.join(repositoryRoot, "apps/operator-web/next.config.ts");
if (fs.existsSync(operatorWebConfigPath)) {
  const operatorWebConfig = fs.readFileSync(operatorWebConfigPath, "utf8");
  for (const workspaceBoundary of ["outputFileTracingRoot: workspaceRoot", "root: workspaceRoot"]) {
    if (!operatorWebConfig.includes(workspaceBoundary)) {
      errors.push(`Operator Web workspace boundary is missing: ${workspaceBoundary}`);
    }
  }
}

const environmentPath = path.join(repositoryRoot, ".env.example");
if (fs.existsSync(environmentPath)) {
  const keys = fs
    .readFileSync(environmentPath, "utf8")
    .split(/\r?\n/)
    .filter((line) => /^[A-Za-z_][A-Za-z0-9_]*=/.test(line))
    .map((line) => line.slice(0, line.indexOf("=")));
  const duplicates = keys.filter((key, index) => keys.indexOf(key) !== index);
  if (duplicates.length > 0) errors.push(`duplicate .env.example keys: ${[...new Set(duplicates)]}`);
  for (const key of ["OPS_CACHE_ROOT", "OPS_ARTIFACT_ROOT", "OPS_DATA_ROOT", "OPS_MODEL_ROOT"]) {
    if (!keys.includes(key)) errors.push(`storage env key is missing: ${key}`);
  }
}

const evidence = prepareValidationEvidence({
  repositoryRoot,
  configuredArtifactRoot: process.env.OPS_ARTIFACT_ROOT,
  configuredEvidencePath: process.env.OPS_LAYOUT_EVIDENCE_PATH,
  defaultRelativePath: path.join("verification", "phase-02", "repository-layout.txt"),
});
if (evidence.error) errors.push(`evidence publication: ${evidence.error}`);

const lines = [
  "OpsMind repository layout validation",
  `TimestampUtc=${new Date().toISOString()}`,
  `FilesChecked=${productFiles.length}`,
  `OpenApiRoots=${openApiFiles.length}`,
  `EvidencePublication=${evidence.evidencePath ? "FILE" : `STDOUT_ONLY_${evidence.reason || "UNSAFE"}`}`,
  `Errors=${errors.length}`,
  ...errors.map((error) => `Error=${error.replace(/[\r\n]/g, " ")}`),
  `Result=${errors.length === 0 ? "PASS" : "BLOCK"}`,
];
const transcript = `${lines.join("\n")}\n`;
process.stdout.write(transcript);

if (evidence.evidencePath) {
  publishValidationEvidence(evidence.evidencePath, transcript);
}

process.exit(errors.length === 0 ? 0 : 1);
