import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  createLocalReferenceResolver,
  countAndValidateSchemaReferences,
} from "./phase-04-incident-contracts/local-reference-resolver.mjs";
import { createContractFileAccess } from "./phase-04-incident-contracts/safe-contract-files.mjs";
import { createSubsetValidator } from "./phase-04-incident-contracts/subset-json-schema-validator.mjs";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "../..");
const contractsRoot = path.join(repositoryRoot, "packages", "contracts");
const schemaRoot = path.join(contractsRoot, "json-schema", "tool-gateway", "v1");
const fixtureRoot = path.join(contractsRoot, "fixtures", "tool-gateway");
const errors = [];
const access = createContractFileAccess(repositoryRoot, errors);
const schemaFiles = access.walkJsonFiles(schemaRoot);
const fixtureFiles = access.walkJsonFiles(fixtureRoot);
const documents = access.parseJsonDocuments([...schemaFiles, ...fixtureFiles]);
const resolveReference = createLocalReferenceResolver({
  contractsRoot,
  documents,
  hasSymlinkFromRoot: access.hasSymlinkFromRoot,
  isWithin: access.isWithin,
});
const referenceCount = countAndValidateSchemaReferences({
  documents,
  errors,
  relativeName: access.relativeName,
  resolveLocalReference: resolveReference,
  schemaFiles,
});
const validate = createSubsetValidator(resolveReference);

function document(relativePath) {
  const absolutePath = path.join(repositoryRoot, relativePath);
  const value = documents.get(path.resolve(absolutePath));
  if (!value) errors.push(`missing parsed contract: ${relativePath}`);
  return { absolutePath, value };
}

function validateFixture(fixturePath, schemaPath, shouldPass) {
  const fixture = document(fixturePath);
  const schema = document(schemaPath);
  if (!fixture.value || !schema.value) return;
  const findings = validate(fixture.value, schema.value, schema.absolutePath);
  if ((findings.length === 0) !== shouldPass) {
    errors.push(`fixture expectation failed: ${fixturePath}`);
  }
}

validateFixture(
  "packages/contracts/fixtures/tool-gateway/tool-execution-request-v1.valid.json",
  "packages/contracts/json-schema/tool-gateway/v1/tool-execution-request.schema.json",
  true,
);
validateFixture(
  "packages/contracts/fixtures/tool-gateway/tool-execution-request-v1.unknown-action.invalid.json",
  "packages/contracts/json-schema/tool-gateway/v1/tool-execution-request.schema.json",
  false,
);
validateFixture(
  "packages/contracts/fixtures/tool-gateway/tool-execution-response-v1.valid.json",
  "packages/contracts/json-schema/tool-gateway/v1/tool-execution-response.schema.json",
  true,
);
validateFixture(
  "packages/contracts/fixtures/tool-gateway/evidence-envelope-v1.valid.json",
  "packages/contracts/json-schema/tool-gateway/v1/evidence-envelope.schema.json",
  true,
);
validateFixture(
  "packages/contracts/fixtures/tool-gateway/delegated-tool-capability-claims-v1.valid.json",
  "packages/contracts/json-schema/tool-gateway/v1/delegated-tool-capability-claims.schema.json",
  true,
);

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  }
  return value;
}

function digest(value) {
  return crypto.createHash("sha256").update(JSON.stringify(canonical(value))).digest("hex");
}

const request = document(
  "packages/contracts/fixtures/tool-gateway/tool-execution-request-v1.valid.json",
).value;
const response = document(
  "packages/contracts/fixtures/tool-gateway/tool-execution-response-v1.valid.json",
).value;
const evidence = document(
  "packages/contracts/fixtures/tool-gateway/evidence-envelope-v1.valid.json",
).value;
if (request && response) {
  const expectedRequestDigest = digest(request);
  if (response.request_digest !== expectedRequestDigest) errors.push("request fixture digest is stale");
}
if (evidence && response) {
  const expectedEvidenceDigest = digest(evidence.content);
  if (evidence.content_digest !== expectedEvidenceDigest) errors.push("evidence fixture digest is stale");
  if (response.evidence?.[0]?.content_digest !== expectedEvidenceDigest) {
    errors.push("response evidence digest is stale");
  }
}

const manifestPath = path.join(
  repositoryRoot,
  "services", "tool-gateway", "src", "main", "resources", "tool-manifests",
  "observability-metrics-query-v1.json",
);
let manifest;
try {
  manifest = JSON.parse(access.readSafeFile(manifestPath));
} catch {
  errors.push("authoritative tool manifest is invalid or unsafe");
}
if (manifest) {
  if (manifest.read_only !== true || manifest.risk_class !== "read-only"
    || manifest.timeout_ms < 1 || manifest.timeout_ms > 30_000
    || !Array.isArray(manifest.egress_targets) || manifest.egress_targets.length !== 1
    || manifest.request_schema_id !== documents.get(path.resolve(
      schemaRoot, "tool-execution-request.schema.json",
    ))?.$id) {
    errors.push("tool manifest safety contract is incomplete or drifted");
  }
}

const openApi = access.readSafeFile(path.join(contractsRoot, "openapi", "opsmind-v1.yaml"));
for (const marker of [
  "  /tools/execute:", "      - url: /internal/v1", "operationId: executeReadOnlyTool",
  "workloadBearer: []", "delegatedCapability: []",
  "../json-schema/tool-gateway/v1/tool-execution-request.schema.json",
  "../json-schema/tool-gateway/v1/tool-execution-response.schema.json",
]) {
  if (!openApi.includes(marker)) errors.push("OpenAPI Tool Gateway contract is incomplete");
}

const sourceRoot = path.join(repositoryRoot, "services", "tool-gateway", "src", "main", "java");
const javaFiles = [];
for (const entry of fs.readdirSync(sourceRoot, { recursive: true, withFileTypes: true })) {
  if (entry.isFile() && entry.name.endsWith(".java")) javaFiles.push(path.join(entry.parentPath, entry.name));
}
const connectorSources = javaFiles
  .filter((file) => file.includes(`${path.sep}connectors${path.sep}`))
  .map((file) => access.readSafeFile(file)).join("\n");
if (/ProcessBuilder|Runtime\.getRuntime|JdbcTemplate|createStatement|Files\.read|new\s+URL/gu.test(connectorSources)) {
  errors.push("connector source exposes a prohibited generic execution primitive");
}
if (fs.existsSync(path.join(sourceRoot, "com"))) errors.push("parallel com.* gateway namespace exists");

function sourceContains(relativePath, markers) {
  const source = access.readSafeFile(path.join(repositoryRoot, relativePath));
  return markers.every((marker) => source.includes(marker));
}

const platformIssuerConformant = sourceContains(
  "services/platform-api/src/main/java/ai/opsmind/platform/delegation/RsaToolCapabilityTokenIssuer.java",
  [
    'claims.put("aud", List.of(audience))',
    'claims.put("azp", authorizedParty)',
    'claims.put("token_use", "delegated_capability")',
    'claims.put("max_calls", 1)',
    'claims.put("request_digest", grant.requestDigest())',
    'claims.put("nonce", signer.randomIdentifier())',
  ],
) && sourceContains(
  "services/platform-api/src/main/java/ai/opsmind/platform/delegation/ToolCapabilityGrant.java",
  ["String requestDigest", "String action", "String resource", "Instant deadlineAt"],
) && sourceContains(
  "services/platform-api/src/test/java/ai/opsmind/platform/delegation/RsaToolCapabilityTokenIssuerTest.java",
  ["delegated-tool-capability-claims-v1.valid.json", "issuesTheStrictGatewayFixtureClaimShape"],
) && sourceContains(
  "services/tool-gateway/src/main/java/ai/opsmind/toolgateway/application/DelegatedCapabilityRequestBinding.java",
  ["requestDigester.digest(request)", "MessageDigest.isEqual"],
);
if (!platformIssuerConformant) {
  errors.push("Platform API capability issuer conformance is incomplete or drifted");
}

const blockers = [
  "durable atomic nonce/receipt/audit/artifact adapters are absent",
  "three fixture connector families are absent",
  "selected live non-production read-only connector proof is absent",
  "provider-specific cancellation and tenant bulkhead proof is absent",
];
const lines = [
  "OpsMind Phase 6 Tool Gateway validation",
  "ValidationScope=DETERMINISTIC_CHECKPOINT_CONTRACT_AND_ABUSE_CHECKS",
  `JsonSchemasParsed=${schemaFiles.length}`,
  `JsonFixturesParsed=${fixtureFiles.length}`,
  `LocalReferencesResolved=${referenceCount}`,
  `Errors=${errors.length}`,
  `CheckpointResult=${errors.length === 0 ? "PASS" : "BLOCK"}`,
  "PhaseExit=BLOCK",
  ...blockers.map((blocker) => `PhaseExitBlocker=${blocker}`),
  ...errors.slice(0, 50).map((error) => `Error=${error}`),
];
process.stdout.write(`${lines.join("\n")}\n`);
process.exit(errors.length === 0 ? 0 : 1);
