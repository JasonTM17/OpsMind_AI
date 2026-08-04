import crypto from "node:crypto";
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
  return access.readSafeFile(absolutePath).replace(/\r\n?/gu, "\n");
}

function requireMarkers(relativePath, markers) {
  const source = read(relativePath);
  for (const marker of markers) {
    if (!source.includes(marker)) errors.push(`${relativePath} misses: ${marker}`);
  }
  return source;
}

function requireExactCount(source, marker, expected, description) {
  const actual = source.split(marker).length - 1;
  if (actual !== expected) {
    errors.push(`${description} expected=${expected} actual=${actual}`);
  }
}

function artifactJavaSources(relativeDirectory) {
  const directory = path.join(repositoryRoot, relativeDirectory);
  if (!fs.existsSync(directory)) {
    errors.push(`missing artifact source directory: ${relativeDirectory}`);
    return [];
  }
  const files = [];
  function visit(currentDirectory) {
    if (access.hasSymlinkFromRoot(currentDirectory)) {
      errors.push(`unsafe artifact source directory: ${access.relativeName(currentDirectory)}`);
      return;
    }
    for (const entry of fs.readdirSync(currentDirectory, { withFileTypes: true })) {
      const entryPath = path.join(currentDirectory, entry.name);
      if (entry.isSymbolicLink()) {
        errors.push(`symlinked artifact source entry: ${access.relativeName(entryPath)}`);
      } else if (entry.isDirectory()) {
        visit(entryPath);
      } else if (entry.isFile() && entry.name.endsWith(".java")) {
        files.push(entryPath);
      }
    }
  }
  visit(directory);
  return files.sort((left, right) => left.localeCompare(right));
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
const normalizedV014 = migration.replace(/\r\n?/gu, "\n");
const normalizedV014Sha256 = crypto.createHash("sha256").update(normalizedV014, "utf8").digest("hex");
if (normalizedV014Sha256 !== "b95ee29742df7b5eed76b73edcf870bcbba773ec1c08078ba55b6575e23f5602") {
  errors.push(`V014 normalized SHA-256 changed: ${normalizedV014Sha256}`);
}

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

requireMarkers(
  "services/platform-api/src/test/java/ai/opsmind/platform/evidence/artifact/"
    + "EvidenceArtifactMetadataPersistenceIntegrationTest.java",
  [
    "TRUNCATE TABLE evidence_artifact_upload_attempts",
    "evidence_artifacts, evidence_artifact_events",
  ],
);

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

const artifactSourceDirectory = "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact";
const artifactJavaFiles = artifactJavaSources(artifactSourceDirectory);
for (const file of artifactJavaFiles) {
  const sourceLines = access.readSafeFile(file).replace(/\r?\n$/u, "").split(/\r?\n/u);
  if (sourceLines.length > 200) {
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

const v015Path = "services/platform-api/src/main/resources/db/migration/"
  + "V015__evidence_artifact_upload_fencing.sql";
const v015 = requireMarkers(v015Path, [
  "ADD COLUMN storage_version_reference varchar(1024)",
  "lower(storage_version_reference) <> 'null'",
  "octet_length(storage_version_reference) <= 1024",
  "evidence_artifacts_phase_2_lifecycle_fence",
  "upload_attempt_count = 0",
  "upload_attempt_count BETWEEN 1 AND 8",
  "CREATE TABLE evidence_artifact_upload_attempts",
  "evidence_artifacts_current_upload_attempt_fk",
  "CREATE OR REPLACE FUNCTION opsmind_claim_evidence_artifact_upload",
  "p_lease_duration_ms NOT BETWEEN 5000 AND 300000",
  "probe_required boolean",
  "reconciliation_required boolean",
  "artifact.lease-expired-unsettled",
  "CREATE OR REPLACE FUNCTION opsmind_settle_evidence_artifact_upload",
  "SECURITY DEFINER",
  "FOR UPDATE OF artifact, incident",
  "FOR UPDATE",
  "attempt_row.status IS DISTINCT FROM 'CLAIMED'",
  "artifact upload settlement lost its active fence",
  "CREATE CONSTRAINT TRIGGER evidence_artifacts_require_stored_event",
  "DEFERRABLE INITIALLY DEFERRED",
  "ARTIFACT_STORED",
  "ALTER TABLE evidence_artifact_upload_attempts FORCE ROW LEVEL SECURITY",
  "REVOKE ALL ON evidence_artifact_upload_attempts",
  "REVOKE UPDATE, DELETE, TRUNCATE ON evidence_artifacts, evidence_artifact_events",
  "GRANT EXECUTE ON FUNCTION public.opsmind_claim_evidence_artifact_upload",
  "GRANT EXECUTE ON FUNCTION public.opsmind_settle_evidence_artifact_upload",
  "transition_at := GREATEST(db_now, artifact_row.lifecycle_updated_at)",
  "settled_at = transition_at",
  "lifecycle_updated_at = transition_at",
]);
if (!v015.includes("p_storage_version_reference IS NULL")
    || !v015.includes("lower(p_storage_version_reference) = 'null'")
    || !v015.includes("octet_length(p_storage_version_reference) > 1024")) {
  errors.push("V015 stored settlement must reject a literal-null version reference");
}

const v018 = requireMarkers(
  "services/platform-api/src/main/resources/db/migration/"
    + "V018__evidence_artifact_lifecycle_controls.sql",
  [
    "TOMBSTONED",
    "evidence_artifacts_phase_3_lifecycle_fence",
    "evidence_artifact_events_phase_3_transition_fence",
    "opsmind_evidence_artifact_control_event_id",
    "CREATE CONSTRAINT TRIGGER evidence_artifacts_require_control_event",
    "ARTIFACT_LIFECYCLE_CHANGED",
    "FOR KEY SHARE OF artifact",
    "opsmind_evidence_artifact_audit_matches_v015",
    "public.opsmind_json_object_has_exact_keys",
    "artifact lifecycle metadata requires its control event and audit row",
    "REVOKE ALL ON FUNCTION public.opsmind_validate_evidence_artifact_update()",
  ],
);
const v018EventAppendFunction = v018.match(
  /CREATE\s+OR\s+REPLACE\s+FUNCTION\s+opsmind_validate_evidence_artifact_event_append\s*\(\s*\)\s+RETURNS\s+trigger[\s\S]*?(?=\n\s*DROP\s+TRIGGER\s+evidence_artifact_events_validate_append\b)/iu,
);
if (!v018EventAppendFunction) {
  errors.push("V018 artifact event append validation function is missing");
} else if (!/\bFOR\s+KEY\s+SHARE\s+OF\s+artifact\b/iu.test(v018EventAppendFunction[0])) {
  errors.push("V018 artifact event append must lock only the authoritative artifact row");
}
requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/"
    + "EvidenceArtifactLifecycleState.java",
  ["TOMBSTONED", "case TOMBSTONED"],
);
const v019 = requireMarkers(
  "services/platform-api/src/main/resources/db/migration/"
    + "V019__evidence_artifact_lifecycle_runtime_capability.sql",
  [
    "SECURITY DEFINER",
    "session_user <> 'opsmind_app'",
    "opsmind_current_tenant_id",
    "opsmind_current_actor_id",
    "clock_timestamp() - interval '5 seconds'",
    "REVOKE ALL ON FUNCTION public.opsmind_transition_evidence_artifact",
    "GRANT EXECUTE ON FUNCTION public.opsmind_transition_evidence_artifact",
  ],
);
const artifactReadService = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/access/"
    + "AuthorizedArtifactReadService.java",
  ["metadata.lifecycleState().isReadable()", "metadata.expectedDigest().equals(probe.digest())",
    "metadata.expectedByteCount() != probe.byteCount()"],
);
const artifactLifecycleService = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/lifecycle/"
    + "ArtifactLifecycleService.java",
  ["@Component", "metadata.authorizationEpoch() != command.authorizationEpoch()",
    "current.canTransitionTo(target)"],
);
const artifactLifecycleRepository = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/lifecycle/"
    + "ArtifactLifecycleRepository.java",
  ["findVisibleForUpdate", "opsmind_transition_evidence_artifact", "evidence_artifact_events",
    "auditRepository.append", "runId"],
);
if (/public\s+final\s+class\s+ArtifactLifecycleRepository/u.test(artifactLifecycleRepository)) {
  errors.push("artifact lifecycle repository must remain proxyable for Spring exception translation");
}
requireMarkers(
  "services/platform-api/src/test/java/ai/opsmind/platform/evidence/artifact/lifecycle/"
    + "ArtifactLifecycleRepositoryTest.java",
  ["persistsMetadataEventAndAuditAsOneAuthorizedTransition",
    "idempotentReceiptDoesNotAppendAnotherEventOrAudit", "hidesMissingArtifactsBeforeMutation",
    "mapsDatabaseFailureToTheSafePersistenceContract"],
);
const artifactReconciliationService = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/lifecycle/"
    + "ArtifactReconciliationService.java",
  ["OBJECT_MATCH", "OBJECT_ABSENT", "OBJECT_MISMATCH", "PURGE_CONFIRMED",
    "command.targetState() != target"],
);
const lifecycleRunner = requireMarkers(
  "scripts/validation/run-phase-04c-artifact-lifecycle-postgres-contract.sh",
  [
    "migrate_to 18",
    "migrate_to 19",
    "LifecycleV018Boundary=PASS",
    "LifecycleV019Capability=PASS",
    "LifecycleMetadataEventAuditAtomicity=PASS",
    "MissingEventAuditRollback=PASS",
    "ArtifactLifecyclePostgresContractResult=PASS",
    "ContractCleanup=PASS",
  ],
);
if (!lifecycleRunner.includes("database must differ from the primary database")) {
  errors.push("artifact lifecycle runner must isolate the V018-to-V019 upgrade database");
}
if (artifactReadService.includes("InputStream") || artifactLifecycleRepository.includes("S3Client")) {
  errors.push("Phase 3 artifact access must remain metadata/probe-only; object I/O belongs to a later adapter");
}

const storageProperties = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/"
    + "storage/EvidenceArtifactStorageProperties.java",
  [
    "MAXIMUM_SUPPORTED_OBJECT_BYTES = 5_000_000_000L",
    "void validateForEnablement()",
    "if (!enabled) return;",
    "allowLoopbackCleartext && \"http\".equalsIgnoreCase(value.getScheme())",
    "literalLoopback(value.getHost())",
    "apiCallAttemptTimeout.compareTo(apiCallTimeout) >= 0",
    "requiredUploadBudget().compareTo(uploadLeaseDuration) < 0",
    ".plus(sourceVerificationBudget)",
    ".plus(settlementSafetyMargin)",
    "between(uploadLeaseDuration, Duration.ofSeconds(5), Duration.ofMinutes(5))",
    "expectedKmsKeyReference",
  ],
);
const storageConfiguration = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/"
    + "storage/S3EvidenceArtifactObjectStorageConfiguration.java",
  [
    "DefaultCredentialsProvider.create()",
    "properties.validateForEnablement()",
    "AwsRetryStrategy.doNotRetry()",
    "@ConditionalOnProperty(",
    "havingValue = \"true\"",
  ],
);
const requestFactory = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/"
    + "storage/S3ArtifactObjectRequestFactory.java",
  [
    ".ifNoneMatch(\"*\")",
    ".checksumSHA256(encodedDigest(expectation))",
    ".serverSideEncryption(ServerSideEncryption.AWS_KMS)",
    ".ssekmsKeyId(properties.kmsKeyId())",
    ".checksumMode(ChecksumMode.ENABLED)",
    "properties.expectedKmsKeyReference().equals(response.ssekmsKeyId())",
    "!value.equalsIgnoreCase(\"null\")",
    "getBytes(StandardCharsets.UTF_8).length <= 1_024",
  ],
);
if (requestFactory.includes(".checksumAlgorithm(")) {
  errors.push("precomputed artifact checksum must not enable SDK checksum recomputation");
}
const objectStorage = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/"
    + "storage/S3EvidenceArtifactObjectStorage.java",
  [
    "ManagedArtifactSource source",
    "hasFullUploadBudget(startedAt, uploadLeaseExpiresAt)",
    "new ManagedArtifactRequestContent(",
    "RequestBody.fromContentProvider(",
    "content.verifyAfterPut()",
    "sourceDeadline(startedAt, uploadLeaseExpiresAt)",
    "finally {\n            release(source);",
    "sourceContractMismatch(failure)",
  ],
);
requireExactCount(objectStorage, "client.putObject(", 1,
  "artifact storage must issue one conditional PUT per invocation");
requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/"
    + "storage/ManagedArtifactSource.java",
  [
    "StandardOpenOption.READ",
    "LinkOption.NOFOLLOW_LINKS",
    "PositionalFileChannelInputStream",
    "cleanupRequested.compareAndSet(false, true)",
  ],
);
requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/"
    + "storage/ManagedArtifactRequestContent.java",
  [
    "MAXIMUM_STREAM_VIEWS = 2",
    "verifyOpenedStreams()",
    "verifyAfterPut()",
    "executor.detachCleanup(source)",
  ],
);
requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/"
    + "storage/ArtifactSourceReadBudget.java",
  ["System.nanoTime()", "remainingNanos()", "absoluteDeadline"],
);
const storedObject = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/"
    + "storage/ArtifactObjectStored.java",
  [
    "!value.equalsIgnoreCase(\"null\")",
    "getBytes(StandardCharsets.UTF_8).length <= 1_024",
  ],
);
const failureMapper = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/"
    + "storage/S3EvidenceArtifactStorageFailureMapper.java",
  [
    "FailureKind.ACCESS_DENIED, true, failure",
    "FailureKind.UNAVAILABLE, true, failure",
    "ArtifactSourceContractViolationException",
    "sourceContractMismatch(failure)",
  ],
);
if (storageProperties.includes("http\".equalsIgnoreCase(value.getScheme())\n            && !literalLoopback")) {
  errors.push("artifact storage cleartext guard permits a non-loopback endpoint");
}
const storageSources = [
  storageProperties, storageConfiguration, requestFactory, objectStorage, storedObject, failureMapper,
].join("\n");
for (const forbiddenStorageCapability of [
  "StaticCredentialsProvider",
  "AwsBasicCredentials",
  "AwsSessionCredentials",
  "access-key",
  "secret-key",
  "session-token",
  "S3Presigner",
]) {
  if (storageSources.includes(forbiddenStorageCapability)) {
    errors.push(`artifact storage contains forbidden credential or URL capability: ${forbiddenStorageCapability}`);
  }
}

const uploadService = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/"
    + "EvidenceArtifactUploadService.java",
  [
    "requireObjectIoOutsideTransaction()",
    "claim.reconciliationRequired()",
    "if (!claim.probeRequired())",
    "storage.probe(claim.expectation())",
    "EvidenceArtifactUploadOutcome.UNCERTAIN",
    "EvidenceArtifactUploadOutcome.ORPHANED",
    "authorizer.withAnalyzeAccess",
    "ManagedArtifactSource content",
    "claim.uploadLeaseExpiresAt()",
    "storage.release(content)",
  ],
);
const uploadSettlementCoordinator = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/"
    + "EvidenceArtifactUploadSettlementCoordinator.java",
  [
    "FailureKind.SOURCE_CONTRACT_MISMATCH",
    "FailureKind.REMOTE_METADATA_MISMATCH",
    "authorizer.withAnalyzeAccess",
    "settlementFailure.addSuppressed(objectFailure)",
  ],
);
requireExactCount(uploadService + uploadSettlementCoordinator, "authorizer.withAnalyzeAccess(", 2,
  "artifact upload must use exactly two independent authorization transactions");
const uploadRepository = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/"
    + "EvidenceArtifactUploadRepository.java",
  [
    "opsmind_claim_evidence_artifact_upload",
    "opsmind_settle_evidence_artifact_upload",
    "requireTransaction();",
    "storedLifecycleAppender.append(claim, settlement)",
  ],
);
if (/S3Client|putObject|headObject|InputStream/iu.test(uploadRepository)) {
  errors.push("upload repository must not own object I/O");
}
const platformExceptionHandler = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/common/api/PlatformExceptionHandler.java",
  ["failureType={} causeType={}", "cause.getClass().getName()"],
);
const classifiedHandlerScope = platformExceptionHandler.split(
  "@ExceptionHandler(Exception.class)",
)[0];
if (/LOGGER\.error\([\s\S]{0,400},\s*exception\s*\)/u.test(classifiedHandlerScope)) {
  errors.push("classified platform failures must not render raw throwable chains");
}
requireMarkers(
  "services/platform-api/src/test/java/ai/opsmind/platform/common/api/"
    + "PlatformExceptionHandlerTest.java",
  [
    "doesNotContain(SENSITIVE_CAUSE_DETAIL, \"sensitive-suppressed-detail\")",
    "causeType=java.lang.IllegalStateException",
  ],
);
const uploadFailureTest = requireMarkers(
  "services/platform-api/src/test/java/ai/opsmind/platform/evidence/artifact/"
    + "EvidenceArtifactUploadFailureTest.java",
  [
    "getCause()).isSameAs(storageFailure)",
    "getSuppressed()).containsExactly(storageFailure)",
    "\"evidence-artifact.settlement-failed\"",
  ],
);
if (uploadFailureTest.includes("getCause()).isNull()")) {
  errors.push("artifact upload failure tests must preserve classified causal exceptions");
}

const application = requireMarkers("services/platform-api/src/main/resources/application.yaml", [
  "enabled: ${OPSMIND_EVIDENCE_ARTIFACT_STORAGE_ENABLED:false}",
  "endpoint: ${OPSMIND_EVIDENCE_ARTIFACT_STORAGE_ENDPOINT:https://s3.invalid.example}",
  "region: ${OPSMIND_EVIDENCE_ARTIFACT_STORAGE_REGION:disabled}",
  "bucket: ${OPSMIND_EVIDENCE_ARTIFACT_STORAGE_BUCKET:disabled}",
  "kms-key-id: ${OPSMIND_EVIDENCE_ARTIFACT_STORAGE_KMS_KEY_ID:}",
  "expected-kms-key-reference: ${OPSMIND_EVIDENCE_ARTIFACT_STORAGE_EXPECTED_KMS_KEY_REFERENCE:}",
  "maximum-object-bytes: ${OPSMIND_EVIDENCE_ARTIFACT_STORAGE_MAXIMUM_OBJECT_BYTES:0}",
  "upload-lease-duration: ${OPSMIND_EVIDENCE_ARTIFACT_STORAGE_UPLOAD_LEASE_DURATION:PT1M}",
  "source-verification-budget: ${OPSMIND_EVIDENCE_ARTIFACT_STORAGE_SOURCE_VERIFICATION_BUDGET:PT5S}",
  "settlement-safety-margin: ${OPSMIND_EVIDENCE_ARTIFACT_STORAGE_SETTLEMENT_SAFETY_MARGIN:PT5S}",
]);
const environmentExample = requireMarkers(".env.example", [
  "OPSMIND_EVIDENCE_ARTIFACT_STORAGE_ENABLED=false",
  "OPSMIND_EVIDENCE_ARTIFACT_STORAGE_ENDPOINT=https://s3.invalid.example",
  "OPSMIND_EVIDENCE_ARTIFACT_STORAGE_REGION=disabled",
  "OPSMIND_EVIDENCE_ARTIFACT_STORAGE_BUCKET=disabled",
  "OPSMIND_EVIDENCE_ARTIFACT_STORAGE_KMS_KEY_ID=",
  "OPSMIND_EVIDENCE_ARTIFACT_STORAGE_EXPECTED_KMS_KEY_REFERENCE=",
  "OPSMIND_EVIDENCE_ARTIFACT_STORAGE_SOURCE_VERIFICATION_BUDGET=PT5S",
  "OPSMIND_EVIDENCE_ARTIFACT_STORAGE_SETTLEMENT_SAFETY_MARGIN=PT5S",
]);
requireMarkers(
  "services/platform-api/src/test/java/ai/opsmind/platform/evidence/artifact/"
    + "storage/S3EvidenceArtifactObjectStorageWireTest.java",
  [
    "Apache5HttpClient.builder()",
    "AwsRetryStrategy.doNotRetry()",
    "assertThat(request.checksum()).isEqualTo(encodedDigest())",
    "assertThat(requestCount).hasValue(1)",
  ],
);
for (const forbiddenEnvironmentField of [
  "AWS_ACCESS_KEY_ID=",
  "AWS_SECRET_ACCESS_KEY=",
  "AWS_SESSION_TOKEN=",
  "OPSMIND_EVIDENCE_ARTIFACT_STORAGE_ACCESS_KEY",
  "OPSMIND_EVIDENCE_ARTIFACT_STORAGE_SECRET",
]) {
  if (application.includes(forbiddenEnvironmentField) || environmentExample.includes(forbiddenEnvironmentField)) {
    errors.push(`artifact storage configuration exposes a credential field: ${forbiddenEnvironmentField}`);
  }
}

const objectRunner = requireMarkers(
  "scripts/validation/run-phase-04c-artifact-object-postgres-contract.sh",
  [
    "OPSMIND_EPHEMERAL_DB=true",
    "FreshPrimaryMigration=PASS",
    "migrate_to 14",
    "migrate_to 15",
    "ArtifactObjectUpgrade=PASS",
    "ArtifactCapabilityGrants=PASS",
    "PendingAttemptShapeConstraint=PASS",
    "ConcurrentClaimSingleWinner=PASS",
    "ExpiredClaimProbeFence=PASS",
    "FailedAttemptImmediateRetry=PASS",
    "OrphanedAttemptReclaimDenial=PASS",
    "--set VERBOSITY=verbose",
    "StaleAttemptSettlement",
    "MissingStoredAuditRollback",
    "\"P7104\"",
    "stored artifact metadata requires its lifecycle event and audit row",
    "StoredAuditRollbackState=PASS",
    "attempt.settled_at IS NOT DISTINCT FROM artifact.lifecycle_updated_at",
    "ExactStoredReplay=PASS",
    "StoredEventAuditAtomicity=PASS",
    "StoredAuditRedaction=PASS",
    "DirectArtifactMutationDenial",
    "DirectAttemptReadDenial",
    "AuthorizationEpochDriftDenial",
    "ContractCleanup=PASS",
  ],
);
if (!objectRunner.includes("upgrade database must differ from the primary database")) {
  errors.push("artifact object runner must isolate the V014-to-V015 upgrade database");
}

const lines = [
  "OpsMind Phase 4C evidence artifact object-lifecycle validation",
  "ValidationScope=METADATA_OBJECT_UPLOAD_FENCING",
  `V014NormalizedSha256=${normalizedV014Sha256}`,
  "ObjectStreaming=PHASE_02_IMPLEMENTED_DEFAULT_OFF",
  "ProductionBackendKmsConformance=EXTERNAL_EVIDENCE_REQUIRED",
  "ArtifactIngress=METADATA_LIFECYCLE_COMMANDS_IMPLEMENTED_OBJECT_IO_DEFERRED",
  "ArtifactReadability=AUTHORIZED_METADATA_PROBE_IMPLEMENTED_OBJECT_STREAM_DEFERRED",
  `Errors=${errors.length}`,
  `CheckpointResult=${errors.length === 0 ? "PASS" : "BLOCK"}`,
  "ArtifactLifecycleExit=BLOCK",
  "ArtifactLifecycleBlocker=B-006/B-008/B-012 remain active",
  ...errors.slice(0, 50).map((error) => `Error=${error}`),
];
process.stdout.write(`${lines.join("\n")}\n`);
process.exit(errors.length === 0 ? 0 : 1);
