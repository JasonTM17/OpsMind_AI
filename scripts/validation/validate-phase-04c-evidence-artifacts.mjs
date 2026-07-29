import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { createContractFileAccess } from "./phase-04-incident-contracts/safe-contract-files.mjs";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const errors = [];
const access = createContractFileAccess(repositoryRoot, errors);

function read(relativePath) {
  const absolutePath = path.join(repositoryRoot, relativePath);
  if (!fs.existsSync(absolutePath)) {
    errors.push(`missing required file: ${relativePath}`);
    return "";
  }
  return access.readSafeFile(absolutePath);
}

function requireMarkers(relativePath, markers) {
  const source = read(relativePath);
  for (const marker of markers) {
    if (!source.includes(marker)) errors.push(`${relativePath} misses: ${marker}`);
  }
  return source;
}

const migrationPath = "services/platform-api/src/main/resources/db/migration/"
  + "V014__evidence_artifact_metadata.sql";
const migration = requireMarkers(migrationPath, [
  "CREATE TABLE evidence_artifacts",
  "CREATE TABLE evidence_artifact_events",
  "PRIMARY KEY (organization_id, artifact_id)",
  "UNIQUE (organization_id, run_id, idempotency_key)",
  "REFERENCES investigation_runs(run_id, organization_id, project_id, incident_id)",
  "evidence_artifacts_phase_1_pending_only",
  "PENDING_UPLOAD",
  "DEFERRABLE INITIALLY DEFERRED",
  "evidence-artifact-audit-v1",
  "ARTIFACT_PENDING_UPLOAD",
  "ALTER TABLE evidence_artifacts FORCE ROW LEVEL SECURITY",
  "ALTER TABLE evidence_artifact_events FORCE ROW LEVEL SECURITY",
  "REVOKE UPDATE, DELETE, TRUNCATE ON evidence_artifacts, evidence_artifact_events",
  "opsmind_evidence_artifact_id",
  "opsmind_evidence_artifact_initial_event_id",
]);

for (const relativePath of [
  "services/platform-api/src/main/java/ai/opsmind/platform/incident/AuthorizedIncidentAnalysisScope.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/EvidenceArtifactMetadataService.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/EvidenceArtifactMetadataRepository.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/EvidenceArtifactAuditPayloadCodec.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/evidence/artifact/EvidenceArtifactDomainTest.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/evidence/artifact/EvidenceArtifactMetadataRepositoryTest.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/evidence/artifact/EvidenceArtifactMetadataPersistenceIntegrationTest.java",
  "scripts/validation/run-phase-04c-artifact-metadata-postgres-contract.sh",
]) read(relativePath);

const authorizer = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentAnalysisAuthorizer.java",
  ["withAnalyzeAccess", "AuthorizedIncidentAnalysisScope.from", "IncidentAccessMode.ANALYZE"],
);
const service = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/"
    + "EvidenceArtifactMetadataService.java",
  ["authorizer.withAnalyzeAccess", "Instant.now().truncatedTo(ChronoUnit.MICROS)"],
);
const repository = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/"
    + "EvidenceArtifactMetadataRepository.java",
  [
    "TransactionSynchronizationManager.isActualTransactionActive",
    "run_row.actor_id = ?",
    "ON CONFLICT",
    "auditRepository.append",
  ],
);
const metadataReader = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/"
    + "EvidenceArtifactMetadataReader.java",
  ["AND actor_id = ?", "authorization_epoch = ?"],
);
const auditCodec = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/"
    + "EvidenceArtifactAuditPayloadCodec.java",
  ["ARTIFACT_PENDING_UPLOAD", "EVIDENCE_ARTIFACT_SCHEMA_VERSION", "contentDigest"],
);
const legacyEvidence = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/CollectedEvidence.java",
  ["artifactReference != null", "EvidenceContentCanonicalizer.MAXIMUM_BYTES"],
);
const upgradeRunner = requireMarkers(
  "scripts/validation/run-phase-04c-artifact-metadata-postgres-contract.sh",
  [
    "migrate_to 13",
    "migrate_to 14",
    "ArtifactEventRlsAfter",
    "AppArtifactStorageKeySelect",
    "ArtifactUpgradeResult=PASS",
    "ContractCleanup=PASS",
  ],
);
if (!migration.includes("NEW.actor_id IS DISTINCT FROM run_row.actor_id")) {
  errors.push("artifact insert trigger must bind metadata actor to the authoritative run owner");
}
const eventAppendFunction = migration.match(
  /CREATE\s+OR\s+REPLACE\s+FUNCTION\s+opsmind_validate_evidence_artifact_event_append\s*\(\s*\)\s+RETURNS\s+trigger[\s\S]*?(?=\n\s*CREATE\s+TRIGGER\s+evidence_artifacts_validate_insert\b)/iu,
);
if (!eventAppendFunction) {
  errors.push("artifact event append validation function is missing");
}
else if (/\bFOR\s+KEY\s+SHARE\b/iu.test(eventAppendFunction[0])) {
  errors.push("artifact event append validation must not require UPDATE privilege on immutable metadata");
}

const sourceRoot = path.join(
  repositoryRoot, "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact",
);
for (const entry of fs.readdirSync(sourceRoot, { withFileTypes: true })) {
  if (!entry.isFile() || !entry.name.endsWith(".java")) continue;
  const file = path.join(sourceRoot, entry.name);
  if (access.readSafeFile(file).split(/\r?\n/u).length > 200) {
    errors.push(`artifact source exceeds 200 lines: ${access.relativeName(file)}`);
  }
}
if (migration.includes("QUARANTED")) errors.push("artifact lifecycle contains a misspelled quarantine state");
if (migration.includes("CREATE TABLE evidence_records")) {
  errors.push("V014 must remain separate from the bounded V007 evidence table");
}
if (/S3Client|InputStream|signed[_ -]?url|object_url/iu.test(
  [service, repository, metadataReader, auditCodec].join("\n"),
)) {
  errors.push("Phase 1 control-plane code must not add storage I/O or URL exposure");
}
if (!legacyEvidence.includes("artifactReference != null")) {
  errors.push("V007 artifact-reference rejection was relaxed before Phase 3");
}
if (!upgradeRunner.includes("OPSMIND_EPHEMERAL_DB")) {
  errors.push("artifact upgrade proof must stay disposable");
}

const lines = [
  "OpsMind Phase 4C evidence artifact metadata validation",
  "ValidationScope=METADATA_AUDIT_RLS_ONLY",
  "ObjectStreaming=DEFERRED_TO_PHASE_02",
  "ArtifactIngress=DEFERRED_TO_PHASE_03",
  `Errors=${errors.length}`,
  `CheckpointResult=${errors.length === 0 ? "PASS" : "BLOCK"}`,
  "ArtifactLifecycleExit=BLOCK",
  "ArtifactLifecycleBlocker=B-006/B-008/B-012 remain active",
  ...errors.slice(0, 50).map((error) => `Error=${error}`),
];
process.stdout.write(`${lines.join("\n")}\n`);
process.exit(errors.length === 0 ? 0 : 1);
