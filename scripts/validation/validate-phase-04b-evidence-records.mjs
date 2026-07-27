import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { createContractFileAccess } from
  "./phase-04-incident-contracts/safe-contract-files.mjs";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "../..");
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

const migration = requireMarkers(
  "services/platform-api/src/main/resources/db/migration/V007__bounded_evidence_records.sql",
  [
    "CREATE TABLE evidence_records",
    "CHECK (content_digest = public.digest(convert_to(canonical_content, 'UTF8'), 'sha256'))",
    "UNIQUE (organization_id, run_id, intent_id)",
    "gateway_duplicate           boolean NOT NULL",
    "REFERENCES investigation_run_events(event_id)",
    "DEFERRABLE INITIALLY DEFERRED",
    "ALTER TABLE evidence_records FORCE ROW LEVEL SECURITY",
    "REVOKE UPDATE, DELETE, TRUNCATE ON evidence_records FROM opsmind_app",
  ],
);
const canonicalizer = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/EvidenceContentCanonicalizer.java",
  ["MAXIMUM_BYTES = 65_536", "MessageDigest.isEqual", "Sensitive evidence fields must already be redacted"],
);
const writer = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/EvidenceRecordWriter.java",
  [
    "EvidenceIdentity.evidenceId", "EvidenceIdentity.executionId",
    "INSERT INTO evidence_records", "matchesExact",
  ],
);
const reader = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/evidence/EvidenceRecordReader.java",
  [
    "public class EvidenceRecordReader",
    "SELECT EXISTS (SELECT 1 FROM investigation_runs",
    "jsonb_array_elements_text",
    "evidence.lifecycle_state = 'AVAILABLE'",
    "canonicalizer.verify",
  ],
);
const ledger = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/investigation/application/InvestigationEventLedger.java",
  ["evidenceWriter.append(state, eventId, evidence)", "auditRepository.append"],
);
const codec = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/investigation/application/InvestigationPersistenceJsonCodec.java",
  ["EvidenceAppendedDetails", "eventDetails(event)"],
);
const upgradeRunner = requireMarkers(
  "scripts/validation/run-phase-04b-migration-upgrade.sh",
  [
    "OPSMIND_EPHEMERAL_DB=true",
    'migrate_to 6',
    'migrate_to 7',
    'migrate_to 8',
    'run_incident_timeline_v009_evidence',
    'ON_ERROR_STOP=1',
    "to_regclass('public.evidence_records')",
    "LegacyPayloadDigestStable=%s",
    "RollingLegacyWriteCount=%s",
    "UpgradeResult=PASS",
    "CleanupResult=PASS",
  ],
);
const v009EvidenceModule = requireMarkers(
  "scripts/validation/phase-04b-evidence-records/incident-timeline-v009-evidence.sh",
  [
    "run_append_benchmark",
    "IncidentActivityTimelineAppendBenchmarkHarnessTest",
    "run_plan_and_read_benchmark",
    "IncidentActivityTimelinePlanHarnessTest",
    "assert_sample_count",
    "POSTGRES_APP_USER",
    "--no-psqlrc",
    "--set ON_ERROR_STOP=1",
    "--set AUTOCOMMIT=on",
    "V009SeedIncidentDistractorRows",
    "V009SeedInvestigationDistractorRows",
    "V009SeedBatchSize=50",
    "V009SeedMaxLocksPerTransaction",
    "V009PostRecoveryIndexCatalog",
    "incident_timeline_activity_order_idx:true",
    "investigation_run_events_activity_order_idx:true",
    '-Dsurefire.useFile=false',
    'V009EvidenceResult=PASS',
  ],
);
const v009Seed = requireMarkers(
  "scripts/validation/phase-04b-evidence-records/incident-timeline-v009-seed.sql",
  [
    "FOR event_version IN 1..49999 LOOP",
    "generate_series(2, 50000, 50)",
    "generate_series(1, 10000, 50)",
    "LEAST(batch_start + 49, 50000)",
    "LEAST(batch_start + 49, 10000)",
    "JOIN phase_v009_distractors fixture ON fixture.run_id = runs.run_id",
    "\\gexec",
    "ANALYZE incident_timeline_events",
    "ANALYZE investigation_run_events",
    "70000000-0000-4000-8000-000000000018",
  ],
);
const v009SeedBatchExecutions = v009Seed.match(/^\\gexec$/gm) ?? [];
if (v009SeedBatchExecutions.length !== 2) {
  errors.push(
    "V009 seed must autocommit exactly two bounded advisory-lock batch families.",
  );
}
const v009DistractorBatchMarker =
  "$batch$, batch_start, LEAST(batch_start + 49, 10000))";
const v009DistractorBatchStart = v009Seed.indexOf(
  "-- Each distractor batch inserts its snapshots and one-event ledgers atomically.",
);
const v009DistractorBatchEnd = v009Seed.indexOf(
  v009DistractorBatchMarker,
  v009DistractorBatchStart,
);
const v009DistractorBatchIsBounded =
  v009DistractorBatchStart >= 0
  && v009DistractorBatchEnd > v009DistractorBatchStart;
const v009DistractorBatch = v009DistractorBatchIsBounded
    ? v009Seed.slice(
        v009DistractorBatchStart,
        v009DistractorBatchEnd + v009DistractorBatchMarker.length,
      )
    : "";
const v009DistractorAtomicSequence = [
  "SELECT format($batch$",
  "WITH runs AS (",
  "INSERT INTO investigation_runs (",
  "FROM phase_v009_distractors",
  "RETURNING run_id",
  "INSERT INTO investigation_run_events (",
  "FROM runs",
  "JOIN phase_v009_distractors fixture ON fixture.run_id = runs.run_id",
];
let v009DistractorSequenceCursor = 0;
for (const marker of v009DistractorAtomicSequence) {
  const markerIndex = v009DistractorBatch.indexOf(
    marker,
    v009DistractorSequenceCursor,
  );
  if (markerIndex < 0) {
    errors.push(
      "V009 distractor snapshots and ledgers must remain in one ordered batch statement.",
    );
    break;
  }
  v009DistractorSequenceCursor = markerIndex + marker.length;
}
const v009DistractorFixtureStart = v009Seed.indexOf(
  "CREATE TEMP TABLE phase_v009_distractors",
);
const v009DistractorPreBatch =
  v009DistractorFixtureStart >= 0 && v009DistractorBatchStart > 0
    ? v009Seed.slice(v009DistractorFixtureStart, v009DistractorBatchStart)
    : "";
const hasPrecommittedDistractorSnapshots =
  /INSERT INTO investigation_runs\s*\([\s\S]*?FROM phase_v009_distractors\s*;/u.test(
    v009DistractorPreBatch,
  );
if (hasPrecommittedDistractorSnapshots) {
  errors.push(
    "V009 distractor snapshots must not commit before their matching ledgers.",
  );
}
const v009SeedFirstBatch = v009Seed.indexOf("SELECT format($batch$");
const v009SeedOuterCommit = v009Seed.indexOf("\nCOMMIT;\n");
if (v009SeedOuterCommit < 0 || v009SeedOuterCommit > v009SeedFirstBatch) {
  errors.push(
    "V009 seed must close its outer transaction before advisory-lock batches.",
  );
}
if (v009Seed.includes("ON COMMIT DROP")) {
  errors.push(
    "V009 distractor temp state must survive the autocommitted batch statements.",
  );
}
if (/DISABLE\s+TRIGGER/iu.test(v009Seed)) {
  errors.push("V009 evidence fixtures must not disable production triggers.");
}
const v009SeedBatchTail = v009Seed.slice(v009SeedFirstBatch);
if (/^\s*BEGIN\s*;/imu.test(v009SeedBatchTail)) {
  errors.push("V009 advisory-lock batches must remain outside an outer transaction.");
}
if (
  v009EvidenceModule.includes("--single-transaction")
  || /(^|\s)-1(\s|$)/u.test(v009EvidenceModule)
) {
  errors.push("V009 seed caller must preserve psql autocommit for bounded batches.");
}
if (
  /^\\set\s+AUTOCOMMIT\s+(off|false|0)\s*$/imu.test(v009Seed)
  || /AUTOCOMMIT=(off|false|0)/iu.test(v009EvidenceModule)
) {
  errors.push("V009 seed must not disable psql autocommit.");
}
const v009Query = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentActivityTimelineQuery.java",
  [
    "static Prepared build(",
    "AND (occurred_at, event_id) > (?, ?)",
    "AND occurred_at >= ?",
    "AND occurred_at > ?",
    "ORDER BY occurred_at ASC, event_id ASC LIMIT ?",
  ],
);
const v009AppendHarness = requireMarkers(
  "services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentActivityTimelineAppendBenchmarkHarnessTest.java",
  [
    "WARMUP_SAMPLES = 50",
    "MEASURED_SAMPLES = 250",
    "JdbcIncidentRepository",
    "JdbcIncidentTimelineRepository",
    "tenantContext.apply",
    "V009AppendHarness=PASS",
  ],
);
const v009PlanHarness = requireMarkers(
  "services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentActivityTimelinePlanHarnessTest.java",
  [
    "IncidentActivityTimelineQuery.build",
    "JdbcIncidentTimelineRepository",
    "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)",
    "CURSOR_RANK_0",
    "CURSOR_RANK_1",
    "V009QueryPlanResult=PASS",
  ],
);
const v009PlanAssertions = requireMarkers(
  "services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentActivityTimelinePlanAssertions.java",
  [
    '"Actual Loops"',
    '"Index Cond"',
    '"Seq Scan", "Bitmap Heap Scan", "Bitmap Index Scan", "Materialize"',
    "hasSizeGreaterThanOrEqualTo(3)",
    "isLessThanOrEqualTo(200)",
  ],
);
requireMarkers(
  "services/platform-api/src/test/java/ai/opsmind/platform/persistence/FlywayRecoveryHarnessTest.java",
  [
    'DROP INDEX CONCURRENTLY public.',
    'flyway.repair();',
    'setTransactionalLock(false);',
    'failedV009History',
    'V009_HISTORY_VERSION = "009"',
    '.target(V009_HISTORY_VERSION);',
    `WHERE version = '" + V009_HISTORY_VERSION`,
    'exactIndexCatalog',
    'OPSMIND_PHASE4B_RECOVERY_ENABLED',
  ],
);
const workflow = requireMarkers(
  ".github/workflows/pr-quality.yml",
  [
    "scripts/validation/run-phase-04b-migration-upgrade.sh",
    "evidence-migration-upgrade.txt",
  ],
);
requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/investigation/application/InvestigationReplayVerifier.java",
  [
    "sameState", "matchesExact", "tool_request_digest IS NOT DISTINCT FROM",
    "InvestigationEventLedger.eventId",
  ],
);

for (const testFile of [
  "services/platform-api/src/test/java/ai/opsmind/platform/evidence/EvidenceContentCanonicalizerTest.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/investigation/application/InvestigationEvidenceEventSerializationTest.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/investigation/application/InvestigationEvidencePersistenceIntegrationTest.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/investigation/application/InvestigationEvidenceRollbackIntegrationTest.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/investigation/application/InvestigationEvidenceReplayIntegrationTest.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/persistence/FlywayRecoveryHarnessTest.java",
]) read(testFile);
requireMarkers(
  "services/platform-api/src/test/java/ai/opsmind/platform/investigation/application/InvestigationEvidenceReplayIntegrationTest.java",
  ["exactReplayIsANoOp", "gatewayRequestDigest", "sourceProvenance() + \"/drift\""],
);
requireMarkers(
  "services/platform-api/src/test/java/ai/opsmind/platform/investigation/application/InvestigationEvidenceRollbackIntegrationTest.java",
  ["invalidCanonicalDigestRollsBack", "auditConflictRollsBackSnapshotEventAndEvidence"],
);

const evidenceRoot = path.join(
  repositoryRoot, "services", "platform-api", "src", "main", "java",
  "ai", "opsmind", "platform", "evidence",
);
const evidenceFiles = fs.readdirSync(evidenceRoot, { withFileTypes: true })
  .filter((entry) => entry.isFile() && entry.name.endsWith(".java"))
  .map((entry) => path.join(evidenceRoot, entry.name));
for (const file of evidenceFiles) {
  if (access.readSafeFile(file).split(/\r?\n/u).length > 200) {
    errors.push(`evidence source exceeds 200 lines: ${access.relativeName(file)}`);
  }
}

const combined = [
  migration, canonicalizer, writer, reader, ledger, codec, upgradeRunner,
  v009EvidenceModule, v009Seed, v009Query, v009AppendHarness, v009PlanHarness,
  v009PlanAssertions, workflow,
].join("\n");
if (/raw_prompt|chain[_-]?of[_-]?thought|provider_api_key/iu.test(combined)) {
  errors.push("evidence checkpoint contains a prohibited sensitive field");
}
if (migration.includes("CREATE TABLE evidence_artifacts")) {
  errors.push("bounded record checkpoint must not pretend to implement the artifact plane");
}
if ((v009EvidenceModule + v009Seed).includes("session_replication_role")) {
  errors.push("V009 evidence must not bypass persistence triggers");
}

const lines = [
  "OpsMind Phase 4B bounded evidence record validation",
  "ValidationScope=BOUNDED_REDACTED_EVIDENCE_RECORD_CHECKPOINT",
  `EvidenceSourceFiles=${evidenceFiles.length}`,
  `Errors=${errors.length}`,
  `CheckpointResult=${errors.length === 0 ? "PASS" : "BLOCK"}`,
  "V009DatabaseGate=ENVIRONMENT_REQUIRED",
  "ArtifactLifecycleExit=BLOCK",
  "ArtifactLifecycleBlocker=B-006/B-008/B-012 remain active",
  ...errors.slice(0, 50).map((error) => `Error=${error}`),
];
process.stdout.write(`${lines.join("\n")}\n`);
process.exit(errors.length === 0 ? 0 : 1);
