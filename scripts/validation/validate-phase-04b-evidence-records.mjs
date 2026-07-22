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
  ["EvidenceIdentity.evidenceId", "EvidenceIdentity.executionId", "INSERT INTO evidence_records"],
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

for (const testFile of [
  "services/platform-api/src/test/java/ai/opsmind/platform/evidence/EvidenceContentCanonicalizerTest.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/investigation/application/InvestigationEvidenceEventSerializationTest.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/investigation/application/InvestigationEvidencePersistenceIntegrationTest.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/investigation/application/InvestigationEvidenceRollbackIntegrationTest.java",
]) read(testFile);

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

const combined = [migration, canonicalizer, writer, reader, ledger, codec].join("\n");
if (/raw_prompt|chain[_-]?of[_-]?thought|provider_api_key/iu.test(combined)) {
  errors.push("evidence checkpoint contains a prohibited sensitive field");
}
if (migration.includes("CREATE TABLE evidence_artifacts")) {
  errors.push("bounded record checkpoint must not pretend to implement the artifact plane");
}

const lines = [
  "OpsMind Phase 4B bounded evidence record validation",
  "ValidationScope=BOUNDED_REDACTED_EVIDENCE_RECORD_CHECKPOINT",
  `EvidenceSourceFiles=${evidenceFiles.length}`,
  `Errors=${errors.length}`,
  `CheckpointResult=${errors.length === 0 ? "PASS" : "BLOCK"}`,
  "ArtifactLifecycleExit=BLOCK",
  "ArtifactLifecycleBlocker=B-006/B-008/B-012 remain active",
  ...errors.slice(0, 50).map((error) => `Error=${error}`),
];
process.stdout.write(`${lines.join("\n")}\n`);
process.exit(errors.length === 0 ? 0 : 1);
