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
const incidentListMigrationPath = path.join(
  repositoryRoot,
  "services", "platform-api", "src", "main", "resources", "db", "migration",
  "V016__incident_list_pagination_indexes.sql",
);
const incidentListMigrationConfigPath = `${incidentListMigrationPath}.conf`;
const incidentPatchMigrationPath = path.join(
  repositoryRoot,
  "services", "platform-api", "src", "main", "resources", "db", "migration",
  "V017__incident_metadata_patch_event.sql",
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
  "packages/contracts/json-schema/incidents/patch-incident-request.schema.json",
  "packages/contracts/json-schema/incidents/transition-incident-request.schema.json",
  "packages/contracts/json-schema/incidents/incident.schema.json",
  "packages/contracts/json-schema/incidents/incident-summary.schema.json",
  "packages/contracts/json-schema/incidents/incident-list-page.schema.json",
  "packages/contracts/json-schema/incidents/incident-timeline-event.schema.json",
  "packages/contracts/json-schema/incidents/incident-timeline-page.schema.json",
  "packages/contracts/json-schema/incidents/incident-activity-timeline-entry.schema.json",
  "packages/contracts/json-schema/incidents/incident-activity-timeline-page.schema.json",
  "packages/contracts/json-schema/audit/audit-event.schema.json",
];
const activityTimelineFields = new Set([
  "eventId",
  "source",
  "eventType",
  "occurredAt",
  "actorId",
  "incidentVersion",
  "investigationRunId",
  "investigationSequence",
]);
const forbiddenActivityTimelineFields = new Set([
  "payload",
  "reason",
  "externalTraceId",
  "operationId",
  "terminalReason",
  "finalResponse",
  "response",
  "evidenceId",
  "evidenceIds",
  "toolId",
  "toolCallId",
  "canonicalContent",
  "credential",
  "credentials",
  "prompt",
  "reasoning",
]);

function sameMembers(actual, expected) {
  return actual.size === expected.size
    && [...actual].every((value) => expected.has(value));
}

function validateActivityTimelineSchemas(documents, schemaRoot, errors) {
  const entryPath = path.join(
    schemaRoot, "incidents", "incident-activity-timeline-entry.schema.json",
  );
  const pagePath = path.join(
    schemaRoot, "incidents", "incident-activity-timeline-page.schema.json",
  );
  const entry = documents.get(entryPath);
  const page = documents.get(pagePath);
  if (!entry || !page) {
    errors.push("incident activity timeline schemas are missing");
    return;
  }

  const incident = entry.$defs?.incidentEntry;
  const investigation = entry.$defs?.investigationEntry;
  const incidentFields = new Set(Object.keys(incident?.properties ?? {}));
  const investigationFields = new Set(Object.keys(investigation?.properties ?? {}));
  const exposedFields = new Set([...incidentFields, ...investigationFields]);
  const incidentRequired = new Set(incident?.required ?? []);
  const investigationRequired = new Set(investigation?.required ?? []);
  if (
    entry.oneOf?.length !== 2
    || incident?.additionalProperties !== false
    || investigation?.additionalProperties !== false
    || !sameMembers(incidentFields, incidentRequired)
    || !sameMembers(investigationFields, investigationRequired)
    || !sameMembers(exposedFields, activityTimelineFields)
  ) {
    errors.push("incident activity timeline entry is not the exact closed eight-field bridge");
  }
  if ([...forbiddenActivityTimelineFields].some((field) => exposedFields.has(field))) {
    errors.push("incident activity timeline entry exposes a forbidden classified field");
  }

  const pageFields = new Set(Object.keys(page.properties ?? {}));
  if (
    page.additionalProperties !== false
    || !sameMembers(pageFields, new Set(["items", "pageSize", "nextPageToken", "hasMore"]))
    || page.properties?.items?.items?.$ref
      !== "./incident-activity-timeline-entry.schema.json"
  ) {
    errors.push("incident activity timeline page is not closed over the activity entry schema");
  }
}

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
validateActivityTimelineSchemas(documents, schemaRoot, errors);
try {
  const incidentListMigration = fileAccess.readSafeFile(incidentListMigrationPath);
  const incidentListMigrationConfig = fileAccess.readSafeFile(incidentListMigrationConfigPath);
  for (const marker of [
    "CREATE INDEX CONCURRENTLY incident_list_order_idx",
    "ON incidents (organization_id, project_id, updated_at DESC, id DESC)",
    "CREATE INDEX CONCURRENTLY incident_list_status_order_idx",
    "ON incidents (organization_id, project_id, status, updated_at DESC, id DESC)",
  ]) {
    if (!incidentListMigration.includes(marker)) {
      errors.push("V016 incident list migration is missing an exact online index contract");
    }
  }
  if (/\b(?:DROP|CREATE\s+TABLE|IF\s+NOT\s+EXISTS)\b/iu.test(incidentListMigration)) {
    errors.push("V016 incident list migration contains forbidden non-additive DDL");
  }
  if (incidentListMigrationConfig.trim() !== "executeInTransaction=false") {
    errors.push("V016 incident list migration must run outside a transaction");
  }
} catch {
  errors.push("V016 incident list migration or sidecar is missing or unsafe");
}
try {
  const incidentPatchMigration = fileAccess.readSafeFile(incidentPatchMigrationPath);
  for (const marker of [
    "opsmind_lock_eligible_incident_owner",
    "session_user <> 'opsmind_app'",
    "FOR SHARE OF member, membership",
    "CREATE OR REPLACE FUNCTION opsmind_validate_incident_write()",
    "metadata patch cannot change incident resolution fields",
    "incident owner must be an active organization member",
    "status transition cannot change incident metadata",
    "INCIDENT_METADATA_PATCHED",
    "CREATE OR REPLACE FUNCTION opsmind_validate_timeline_append()",
    "DROP CONSTRAINT audit_events_incident_contract",
    "GRANT UPDATE (title, description, severity, owner_id) ON incidents TO opsmind_app",
  ]) {
    if (!incidentPatchMigration.includes(marker)) {
      errors.push("V017 incident patch migration is missing an authority marker");
    }
  }
  if (/\b(?:CREATE\s+TABLE|TRUNCATE)\b/iu.test(incidentPatchMigration)) {
    errors.push("V017 incident patch migration contains forbidden destructive DDL");
  }
} catch {
  errors.push("V017 incident patch migration is missing or unsafe");
}
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
      incidentListMigrationPath,
      incidentListMigrationConfigPath,
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
