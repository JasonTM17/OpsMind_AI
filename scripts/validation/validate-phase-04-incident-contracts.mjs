import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  prepareValidationEvidence,
  publishValidationEvidence,
} from "./safe-validation-evidence.mjs";
import {
  fixtureCases,
  validateFixtureCases,
} from "./phase-04-incident-contracts/fixture-contract-validator.mjs";
import {
  countAndValidateSchemaReferences,
  createLocalReferenceResolver,
} from "./phase-04-incident-contracts/local-reference-resolver.mjs";
import { validateOpenApi } from "./phase-04-incident-contracts/openapi-static-contract-validator.mjs";
import { createContractFileAccess } from "./phase-04-incident-contracts/safe-contract-files.mjs";
import { inspectIncidentSchemas } from "./phase-04-incident-contracts/schema-contract-inspector.mjs";
import { createSubsetValidator } from "./phase-04-incident-contracts/subset-json-schema-validator.mjs";
import {
  inspectAuditPersistenceContracts,
} from "./phase-04-incident-contracts/audit-persistence-contract-inspector.mjs";
import {
  createPhase4EvidenceMetadata,
} from "./phase-04-incident-contracts/evidence-metadata.mjs";

const startedAt = new Date();

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "../..");
const contractsRoot = path.join(repositoryRoot, "packages", "contracts");
const schemaRoot = path.join(contractsRoot, "json-schema");
const fixtureRoot = path.join(contractsRoot, "fixtures");
const openApiPath = path.join(contractsRoot, "openapi", "opsmind-v1.yaml");
const migrationPath = path.join(
  repositoryRoot,
  "services", "platform-api", "src", "main", "resources", "db", "migration",
  "V003__incident_control_plane.sql",
);
const portableRunnerPath = path.join(
  repositoryRoot, "scripts", "validation", "run-phase-04-postgres-contract.sh",
);
const windowsRunnerPath = path.join(
  repositoryRoot, "scripts", "validation", "run-phase-04-local-postgres-contract.ps1",
);
const auditRepositoryPath = path.join(
  repositoryRoot,
  "services", "platform-api", "src", "main", "java", "ai", "opsmind", "platform",
  "audit", "TransactionalAuditRepository.java",
);
const errors = [];
const requiredSchemaPaths = [
  "packages/contracts/json-schema/incidents/incident-types.schema.json",
  "packages/contracts/json-schema/incidents/create-incident-request.schema.json",
  "packages/contracts/json-schema/incidents/transition-incident-request.schema.json",
  "packages/contracts/json-schema/incidents/incident.schema.json",
  "packages/contracts/json-schema/incidents/incident-timeline-event.schema.json",
  "packages/contracts/json-schema/incidents/incident-timeline-page.schema.json",
  "packages/contracts/json-schema/audit/audit-event.schema.json",
];

const fileAccess = createContractFileAccess(repositoryRoot, errors);
const schemaFiles = fileAccess.walkJsonFiles(schemaRoot);
const fixtureFiles = fileAccess.walkJsonFiles(fixtureRoot);
const documents = fileAccess.parseJsonDocuments([...schemaFiles, ...fixtureFiles]);
const resolveLocalReference = createLocalReferenceResolver({
  contractsRoot,
  documents,
  hasSymlinkFromRoot: fileAccess.hasSymlinkFromRoot,
  isWithin: fileAccess.isWithin,
});

let localReferenceCount = countAndValidateSchemaReferences({
  documents,
  errors,
  relativeName: fileAccess.relativeName,
  resolveLocalReference,
  schemaFiles,
});
inspectIncidentSchemas({
  documents,
  errors,
  relativeName: fileAccess.relativeName,
  repositoryRoot,
  requiredSchemaPaths,
  schemaRoot,
});
try {
  inspectAuditPersistenceContracts({
    migration: fileAccess.readSafeFile(migrationPath),
    portableRunner: fileAccess.readSafeFile(portableRunnerPath),
    windowsRunner: fileAccess.readSafeFile(windowsRunnerPath),
    auditRepository: fileAccess.readSafeFile(auditRepositoryPath),
    errors,
  });
} catch {
  errors.push("audit persistence contract input is missing or unsafe");
}
validateFixtureCases({
  documents,
  errors,
  fixtureFiles,
  fixtureRoot,
  schemaRoot,
  validateInstance: createSubsetValidator(resolveLocalReference),
});

let openApi = "";
let openApiOperationCount = 0;
try {
  openApi = fileAccess.readSafeFile(openApiPath);
  const openApiResult = validateOpenApi({
    openApi,
    openApiPath,
    errors,
    resolveLocalReference,
  });
  openApiOperationCount = openApiResult.operationCount;
  localReferenceCount += openApiResult.referenceCount;
} catch {
  errors.push("OpenAPI document is missing or unsafe");
}

const evidence = prepareValidationEvidence({
  repositoryRoot,
  configuredArtifactRoot: process.env.OPS_ARTIFACT_ROOT,
  configuredEvidencePath: process.env.OPS_PHASE_04_EVIDENCE_PATH,
  defaultRelativePath: path.join("verification", "phase-04", "incident-contracts.txt"),
});
if (evidence.error) errors.push(`evidence publication: ${evidence.error}`);

const positiveCases = fixtureCases.filter(([, , shouldPass]) => shouldPass).length;
let metadata = [];
try {
  metadata = createPhase4EvidenceMetadata({
    repositoryRoot,
    contractFiles: [
      ...schemaFiles,
      ...fixtureFiles,
      openApiPath,
      portableRunnerPath,
      windowsRunnerPath,
      auditRepositoryPath,
    ],
    migrationPath,
    startedAt,
  });
} catch {
  errors.push("evidence metadata could not be computed safely");
}
const boundedErrors = errors.slice(0, 50).map((error) =>
  error.replace(/[\r\n]/gu, " ").slice(0, 500)
);
const lines = [
  "OpsMind Phase 4 incident-contract validation",
  ...metadata,
  "ValidationScope=DETERMINISTIC_OFFLINE_CONTRACT_CHECKS",
  "OpenApiValidation=STATIC_OPERATION_AND_REFERENCE_CONTRACTS",
  `JsonSchemasParsed=${schemaFiles.length}`,
  `JsonFixturesParsed=${fixtureFiles.length}`,
  `FixturePositiveCases=${positiveCases}`,
  `FixtureNegativeCases=${fixtureCases.length - positiveCases}`,
  `LocalReferencesResolved=${localReferenceCount}`,
  `OpenApiOperations=${openApiOperationCount}`,
  `Errors=${errors.length}`,
  `DiagnosticsReported=${boundedErrors.length}`,
  `DiagnosticsTruncated=${boundedErrors.length < errors.length ? "YES" : "NO"}`,
  ...boundedErrors.map((error) => `Error=${error}`),
  `Result=${errors.length === 0 ? "PASS" : "BLOCK"}`,
];
const transcript = `${lines.join("\n")}\n`;
process.stdout.write(transcript);
if (evidence.evidencePath) publishValidationEvidence(evidence.evidencePath, transcript);
process.exit(errors.length === 0 ? 0 : 1);
