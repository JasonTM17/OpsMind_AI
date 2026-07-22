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
const schemaRoot = path.join(contractsRoot, "json-schema", "investigation", "v1");
const fixtureRoot = path.join(contractsRoot, "fixtures", "investigation");
const errors = [];
const access = createContractFileAccess(repositoryRoot, errors);
const schemaFiles = access.walkJsonFiles(schemaRoot);
const fixtureFiles = access.walkJsonFiles(fixtureRoot);
const dependencySchemas = [path.join(
  contractsRoot, "json-schema", "ai-runtime", "v1", "analysis-response.schema.json",
)];
const documents = access.parseJsonDocuments([
  ...schemaFiles, ...fixtureFiles, ...dependencySchemas,
]);
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

function validateFixture(fixturePath, schemaPath) {
  const fixture = document(fixturePath);
  const schema = document(schemaPath);
  if (!fixture.value || !schema.value) return;
  const findings = validate(fixture.value, schema.value, schema.absolutePath);
  if (findings.length > 0) errors.push(`valid fixture rejected: ${fixturePath}`);
}

validateFixture(
  "packages/contracts/fixtures/investigation/start-investigation-request-v1.valid.json",
  "packages/contracts/json-schema/investigation/v1/investigation-run.schema.json",
);
validateFixture(
  "packages/contracts/fixtures/investigation/investigation-view-v1.valid.json",
  "packages/contracts/json-schema/investigation/v1/investigation-view.schema.json",
);

const view = document(
  "packages/contracts/fixtures/investigation/investigation-view-v1.valid.json",
).value;
if (view) {
  const evidence = new Set(view.evidenceIds ?? []);
  const citations = view.analysis?.citations ?? [];
  if (view.status === "COMPLETED" && citations.length === 0) {
    errors.push("completed investigation fixture has no citations");
  }
  if (citations.some((citation) => !evidence.has(citation.evidence_id))) {
    errors.push("completed investigation fixture cites unpersisted evidence");
  }
}

const openApi = access.readSafeFile(path.join(contractsRoot, "openapi", "opsmind-v1.yaml"));
for (const marker of [
  "operationId: startInvestigation",
  "operationId: getInvestigation",
  "../json-schema/investigation/v1/investigation-run.schema.json",
  "../json-schema/investigation/v1/investigation-view.schema.json",
]) {
  if (!openApi.includes(marker)) errors.push("OpenAPI investigation contract is incomplete");
}

const sourceRoot = path.join(
  repositoryRoot, "services", "platform-api", "src", "main", "java",
  "ai", "opsmind", "platform", "investigation",
);
const sourceFiles = fs.readdirSync(sourceRoot, { recursive: true, withFileTypes: true })
  .filter((entry) => entry.isFile() && entry.name.endsWith(".java"))
  .map((entry) => path.join(entry.parentPath, entry.name));
const source = sourceFiles.map((file) => access.readSafeFile(file)).join("\n");
for (const marker of [
  "InvestigationStateMachine",
  "The same tool intent was requested twice.",
  "Final analysis contains an unpersisted citation.",
  "@Profile(\"fixture\")",
]) {
  if (!source.includes(marker)) errors.push(`investigation invariant missing: ${marker}`);
}
if (/ProcessBuilder|Runtime\.getRuntime|JdbcTemplate|createStatement|chain[_-]?of[_-]?thought/iu.test(source)) {
  errors.push("investigation checkpoint exposes a prohibited primitive or reasoning field");
}

const applicationConfiguration = access.readSafeFile(path.join(
  repositoryRoot, "services", "platform-api", "src", "main", "resources", "application.yaml",
));
if (!applicationConfiguration.includes("enabled: ${OPSMIND_INVESTIGATION_V1_ENABLED:false}")) {
  errors.push("investigation feature flag is not fail-closed by default");
}

const overlongSource = sourceFiles.find((file) =>
  access.readSafeFile(file).split(/\r?\n/u).length > 200
);
if (overlongSource) errors.push(`investigation source exceeds 200 lines: ${access.relativeName(overlongSource)}`);

const blockers = [
  "durable tenant-scoped run/event/timeline/audit persistence is absent",
  "real Platform API capability-backed AI Runtime and Tool Gateway clients are absent",
  "selected live non-production read-only connector evidence is absent",
  "CK/Stitch operator investigation UI and browser E2E proof are absent",
  "cross-service trace and p95 benchmark evidence are absent",
];
const lines = [
  "OpsMind Phase 7 investigation slice validation",
  "ValidationScope=DETERMINISTIC_REDUCER_FIXTURE_CONTRACT_CHECKPOINT",
  `JsonSchemasParsed=${schemaFiles.length}`,
  `JsonFixturesParsed=${fixtureFiles.length}`,
  `LocalReferencesResolved=${referenceCount}`,
  `SourceFilesChecked=${sourceFiles.length}`,
  `Errors=${errors.length}`,
  `CheckpointResult=${errors.length === 0 ? "PASS" : "BLOCK"}`,
  "PhaseExit=BLOCK",
  ...blockers.map((blocker) => `PhaseExitBlocker=${blocker}`),
  ...errors.slice(0, 50).map((error) => `Error=${error}`),
];
process.stdout.write(`${lines.join("\n")}\n`);
process.exit(errors.length === 0 ? 0 : 1);
