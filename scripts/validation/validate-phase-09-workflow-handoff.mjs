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
    if (!source.includes(marker)) {
      errors.push(`${relativePath} misses: ${marker}`);
    }
  }
  return source;
}

const migrationPath =
  "services/platform-api/src/main/resources/db/migration/"
  + "V010__investigation_workflow_start_handoff.sql";
const migration = requireMarkers(migrationPath, [
  "CREATE TABLE investigation_workflow_bindings",
  "ALTER TABLE investigation_workflow_bindings FORCE ROW LEVEL SECURITY",
  "CREATE TRIGGER investigation_workflow_bindings_validate_write",
  "CREATE TRIGGER outbox_events_validate_investigation_workflow_start",
  "oppsmind_investigation_workflow_start_event_id".replace("opps", "ops"),
  "convert_from(NEW.payload_bytes, 'UTF8')::jsonb IS DISTINCT FROM NEW.payload",
  "workflow authorization revision must match the incident snapshot",
  "OWNER TO opsmind_dispatch_resolver",
  "GRANT SELECT, INSERT ON investigation_workflow_bindings TO opsmind_app",
  "TO opsmind_dispatcher",
  "event_row.event_type = 'investigation.workflow-start.requested'",
]);

const hardeningMigrationPath =
  "services/platform-api/src/main/resources/db/migration/"
  + "V011__investigation_workflow_dispatch_safety_fence.sql";
const hardeningMigration = requireMarkers(hardeningMigrationPath, [
  "CREATE OR REPLACE FUNCTION opsmind_lock_eligible_investigation_dispatcher",
  "CREATE OR REPLACE FUNCTION opsmind_preflight_investigation_workflow_start",
  "CREATE OR REPLACE FUNCTION opsmind_settle_investigation_workflow_start",
  "CREATE OR REPLACE FUNCTION opsmind_terminalize_unclaimed_ineligible_workflow_starts",
  "RETURN 'workflow.lease-lost';",
  "RETURN 'workflow.lease-window-exhausted';",
  "RETURN 'workflow.deadline-exhausted';",
  "RETURN 'workflow.dispatcher-ineligible';",
  "RETURN 'workflow.authorization-revoked';",
  "RETURN 'workflow.preflight-allowed';",
  "OWNER TO opsmind_context_resolver",
  "OWNER TO opsmind_dispatch_resolver",
  "TO opsmind_dispatcher",
]);
if (hardeningMigration.includes("opsmind_current_tenant_id")) {
  errors.push("V011 preflight must not require or establish a tenant context");
}

const migrationDirectory = path.join(
  repositoryRoot,
  "services/platform-api/src/main/resources/db/migration",
);
const workflowMigrations = fs.readdirSync(migrationDirectory)
  .filter((name) => /workflow.*(?:handoff|safety)|(?:handoff|safety).*workflow/iu.test(name))
  .sort();
if (
  workflowMigrations.length !== 2
  || workflowMigrations[0] !== "V010__investigation_workflow_start_handoff.sql"
  || workflowMigrations[1] !== "V011__investigation_workflow_dispatch_safety_fence.sql"
) {
  errors.push("the workflow handoff must have exactly V010 and V011 migration owners");
}

const pom = requireMarkers("services/platform-api/pom.xml", [
  "<temporal.version>1.35.0</temporal.version>",
  "<artifactId>temporal-sdk</artifactId>",
  "<artifactId>temporal-testing</artifactId>",
  "<version>${temporal.version}</version>",
]);
const temporalVersionDeclarations =
  pom.match(/<temporal\.version>1\.35\.0<\/temporal\.version>/gu) ?? [];
if (temporalVersionDeclarations.length !== 1) {
  errors.push("Temporal 1.35.0 must have one authoritative Maven pin");
}

requireMarkers("services/platform-api/src/main/resources/application.yaml", [
  "execution-mode: ${OPSMIND_INVESTIGATION_EXECUTION_MODE:inline}",
  "enabled: ${OPSMIND_INVESTIGATION_TEMPORAL_CLIENT_ENABLED:false}",
  "enabled: ${OPSMIND_INVESTIGATION_WORKFLOW_STARTER_ENABLED:false}",
  "enabled: ${OPSMIND_DISPATCHER_ENABLED:false}",
  "url: ${OPSMIND_DISPATCHER_DB_URL:disabled}",
  "batch-size: ${OPSMIND_INVESTIGATION_WORKFLOW_STARTER_BATCH_SIZE:1}",
]);
requireMarkers(".env.example", [
  "OPSMIND_INVESTIGATION_EXECUTION_MODE=inline",
  "OPSMIND_INVESTIGATION_TEMPORAL_CLIENT_ENABLED=false",
  "OPSMIND_INVESTIGATION_WORKFLOW_STARTER_ENABLED=false",
  "OPSMIND_DISPATCHER_ENABLED=false",
  "OPSMIND_DISPATCHER_DB_URL=disabled",
  "OPSMIND_INVESTIGATION_WORKFLOW_STARTER_BATCH_SIZE=1",
]);

const requestPath =
  "services/platform-api/src/main/java/ai/opsmind/platform/investigation/"
  + "workflow/InvestigationWorkflowStartRequest.java";
const request = requireMarkers(requestPath, [
  "public record InvestigationWorkflowStartRequest",
  "@JsonPropertyOrder",
  "authorization_revision",
  "request_digest",
]);
const serializedFields = [
  ...request.matchAll(/@JsonProperty\("([^"]+)"\)/gu),
].map((match) => match[1]);
const expectedFields = [
  "organization_id",
  "project_id",
  "incident_id",
  "run_id",
  "actor_id",
  "max_rounds",
  "max_tool_calls",
  "max_evidence_items",
  "max_tokens",
  "started_at",
  "deadline_at",
  "temporal_cluster_id",
  "temporal_namespace",
  "workflow_id",
  "workflow_type",
  "task_queue",
  "authorization_revision",
  "request_digest",
];
if (JSON.stringify(serializedFields) !== JSON.stringify(expectedFields)) {
  errors.push("workflow start payload fields differ from the approved ordered contract");
}
const prohibitedFields = new Set([
  "prompt",
  "raw_prompt",
  "evidence",
  "evidence_body",
  "bearer_token",
  "api_key",
  "secret",
  "provider_request",
  "capability_token",
  "chain_of_thought",
]);
for (const field of serializedFields) {
  if (prohibitedFields.has(field)) {
    errors.push(`workflow history contains prohibited field: ${field}`);
  }
}

requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/investigation/"
    + "workflow/InvestigationWorkflowStartEnvelopeFactory.java",
  [
    'AGGREGATE_TYPE = "investigation-workflow"',
    'EVENT_TYPE = "investigation.workflow-start.requested"',
    'SCHEMA_VERSION = "1"',
    "UUID.nameUUIDFromBytes",
    "RequestDigest.sha256(payloadBytes)",
  ],
);
requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/investigation/"
    + "workflow/TemporalInvestigationWorkflowClient.java",
  [
    "WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE",
    "WORKFLOW_ID_CONFLICT_POLICY_FAIL",
    "readFirstStartInput",
    "streamHistory",
    "workflow.existing-contract-unverifiable",
  ],
);
requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/investigation/"
    + "workflow/InvestigationWorkflowDispatchTransactions.java",
  [
    "dispatcherTransactionManager",
    "opsmind_preflight_investigation_workflow_start",
    "opsmind_settle_investigation_workflow_start",
    "opsmind_terminalize_unclaimed_ineligible_workflow_starts",
    "Workflow starter must not claim more than one lease per transaction.",
    "private <T> T inDispatcher",
    "private <T> T inTenant",
  ],
);

requireMarkers(
  "scripts/operations/investigation-workflow-cutover-inventory.sql",
  [
    "REPEATABLE READ READ ONLY",
    "starts_must_be_frozen",
    "eligible_for_automatic_backfill",
    "legacy_request_digest_and_authorization_revision_not_persisted",
    "\\quit 3",
  ],
);
requireMarkers("scripts/validation/run-phase-04b-migration-upgrade.sh", [
  "migrate_to 10",
  "migrate_to 11",
  "CutoverBlockExit=%s",
  "NonterminalOrphansAfterReconciliation=%s",
  "VersionEleven=%s",
  "WorkflowSettlementFunctionAfterEleven=%s",
  "WorkflowSettlementOwnerAfterEleven=%s",
]);

const requiredTests = {
  ["services/platform-api/src/test/java/ai/opsmind/platform/investigation/"
    + "application/InvestigationWorkflowHandoffPersistenceIntegrationTest.java"]: [
    "injectedFailureAtEveryInsertBoundaryRollsBackWholeHandoff",
    "unresolvedLegacyRunBlocksNewTemporalAdmission",
  ],
  ["services/platform-api/src/test/java/ai/opsmind/platform/investigation/"
    + "workflow/InvestigationWorkflowDispatcherPersistenceIntegrationTest.java"]: [
    "databaseClockDeadlineFenceRejectsWithoutTemporalRpc",
    "remainingLeaseWindowPreventsAnotherTemporalRpc",
    "dispatcherAuthorizationPreflightDeniesRevokedProjectRole",
    "suspendedAccountCanTerminallySettleItsAlreadyClaimedWorkflowLease",
    "suspendedAccountCanSettleAConfirmedTemporalStart",
    "suspendedAccountPreservesAmbiguousRetryForReconciliation",
    "terminalizerPoisonsAnUnclaimedStartWithNoEligibleDispatcher",
    "claimProcessesOnlyOneLeasePerTransaction",
    "expiredLeaseCannotPartiallyAcknowledgeBindingInboxOrOutbox",
    "permanentFailureRejectsBindingInboxAndOutboxInOneTransaction",
    "moreThanOneHundredUnrelatedReadyTenantsCannotStarveWorkflowStarts",
  ],
  ["services/platform-api/src/test/java/ai/opsmind/platform/investigation/"
    + "workflow/InvestigationWorkflowStartDispatcherTest.java"]: [
    "preflightTerminalDecisionRejectsWithoutInvokingWorkflowClient",
    "stalePreflightSkipsWorkflowClientAndLeaseMutation",
  ],
  ["services/platform-api/src/test/java/ai/opsmind/platform/investigation/"
    + "workflow/TemporalInvestigationWorkflowClientTest.java"]: [
    "matchingMemoCannotHideDifferentFirstStartInput",
  ],
  ["services/platform-api/src/test/java/ai/opsmind/platform/investigation/"
    + "workflow/TemporalInvestigationWorkflowHistoryLeakTest.java"]: [
    "startHistoryContainsOnlyTheApprovedBoundedContract",
    "evidence_body",
    "bearer_token",
    "provider_request",
  ],
  ["services/platform-api/src/test/java/ai/opsmind/platform/investigation/"
    + "application/DurableInvestigationExecutionStarterTest.java"]: [
    "exactExistingBindingRemainsReadableWhenWorkerReadinessIsLost",
  ],
};
for (const [relativePath, markers] of Object.entries(requiredTests)) {
  requireMarkers(relativePath, markers);
}

const workflow = requireMarkers(".github/workflows/pr-quality.yml", [
  "validate-phase-09-workflow-handoff.mjs",
  "OPSMIND_PHASE9_DB_INTEGRATION",
  "InvestigationWorkflowHandoffPersistenceIntegrationTest",
  "InvestigationWorkflowDispatcherPersistenceIntegrationTest",
  "surefire.failIfNoSpecifiedTests=true",
  "postgres-contracts.txt",
]);

const staleV005 = read(
  "services/platform-api/src/main/resources/db/migration/"
    + "V005__ai_runtime_capability_probe_audit.sql",
);
if (/investigation[_ .-]?workflow|temporal/iu.test(staleV005)) {
  errors.push("workflow handoff ownership drifted into legacy V005");
}
const pythonRoot = path.join(repositoryRoot, "services/ai-runtime");
const pythonFiles = fs.readdirSync(pythonRoot, { recursive: true })
  .filter((name) => typeof name === "string" && name.endsWith(".py"));
for (const name of pythonFiles) {
  const source = access.readSafeFile(path.join(pythonRoot, name));
  if (/from\s+temporalio|import\s+temporalio|@workflow\.(?:defn|run)/u.test(source)) {
    errors.push(`Python AI Runtime must not own the Temporal workflow: ${name}`);
  }
}
if (fs.existsSync(path.join(repositoryRoot, "app"))) {
  errors.push("stale root app/ workflow path must not exist");
}

if (!workflow.includes("artifacts/verification/phase-09-workflow-handoff")) {
  errors.push("PR quality does not retain bounded Phase 9 evidence");
}
if (!migration.includes("REVOKE ALL ON investigation_workflow_bindings")) {
  errors.push("workflow binding table lacks explicit privilege reset");
}

const lines = [
  "OpsMind Phase 9 workflow handoff validation",
  `MigrationOwner=${path.basename(migrationPath)}`,
  `HardeningMigrationOwner=${path.basename(hardeningMigrationPath)}`,
  `TemporalVersionPins=${temporalVersionDeclarations.length}`,
  `SerializedPayloadFields=${serializedFields.length}`,
  `RequiredTestFiles=${Object.keys(requiredTests).length}`,
  `Errors=${errors.length}`,
  `WorkflowHandoffResult=${errors.length === 0 ? "PASS" : "BLOCK"}`,
  ...errors.slice(0, 50).map((error) => `Error=${error}`),
];
process.stdout.write(`${lines.join("\n")}\n`);
process.exit(errors.length === 0 ? 0 : 1);
