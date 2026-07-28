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

const exclusivityMigrationPath =
  "services/platform-api/src/main/resources/db/migration/"
  + "V012__investigation_workflow_dispatch_exclusivity.sql";
const exclusivityMigration = requireMarkers(exclusivityMigrationPath, [
  "REVOKE ALL ON TABLE public.investigation_workflow_bindings FROM opsmind_dispatcher",
  "REVOKE ALL ON TABLE public.inbox_events FROM opsmind_dispatcher",
  "pg_catalog.pg_auth_members",
  "membership.roleid = role_row.oid",
  "member_role.rolname <> session_user",
  "membership.admin_option",
  "membership.inherit_option",
  "membership.set_option",
  "unsafe attributes or role memberships",
  "status, temporal_run_id, rejection_code, updated_at, temporal_started_at, rejected_at",
  "status, processed_at, attempts, last_error",
  "CREATE POLICY outbox_events_dispatcher_excludes_investigation_workflow_start",
  "AS RESTRICTIVE",
  "FOR ALL TO opsmind_dispatcher",
  "CREATE OR REPLACE FUNCTION opsmind_has_unpublished_outbox_predecessor",
  "outbox predecessor identity is invalid",
  "outbox predecessor lookup requires its bound tenant",
  "CREATE OR REPLACE FUNCTION opsmind_claim_investigation_workflow_start",
  "CREATE OR REPLACE FUNCTION opsmind_list_investigation_workflow_start_tenants",
  "OWNER TO opsmind_dispatch_resolver",
  "REVOKE ALL ON FUNCTION public.opsmind_claim_investigation_workflow_start",
  "GRANT EXECUTE ON FUNCTION public.opsmind_claim_investigation_workflow_start",
  "GRANT SELECT (last_error) ON outbox_events TO opsmind_context_resolver",
  "workflow.ambiguous-retry-allowed",
  "workflow.reconciliation-required",
  "workflow.temporal-outcome-ambiguous",
  "SET last_error = 'workflow.temporal-outcome-ambiguous'",
  "event_row.attempts > 0",
  "event_row.last_error = 'workflow.temporal-unavailable'",
  "event_row.last_error IN (",
  "event_row.last_error IS DISTINCT FROM 'workflow.reconciliation-required'",
]);
if (!exclusivityMigration.includes("UPDATE (attempts) ON outbox_events")) {
  errors.push("V012 resolver claim must receive only its attempt update capability");
}
for (const column of [
  "causation_id",
  "correlation_id",
  "payload_bytes",
  "payload_digest",
  "lease_token",
  "attempts",
]) {
  if (!exclusivityMigration.includes(column)) {
    errors.push(`V012 resolver claim is missing required envelope column: ${column}`);
  }
}

const migrationDirectory = path.join(
  repositoryRoot,
  "services/platform-api/src/main/resources/db/migration",
);
const workflowMigrations = fs.readdirSync(migrationDirectory)
  .filter((name) => /^V01[0-2]__investigation_workflow_/u.test(name))
  .sort();
if (
  workflowMigrations.length !== 3
  || workflowMigrations[0] !== "V010__investigation_workflow_start_handoff.sql"
  || workflowMigrations[1] !== "V011__investigation_workflow_dispatch_safety_fence.sql"
  || workflowMigrations[2] !== "V012__investigation_workflow_dispatch_exclusivity.sql"
) {
  errors.push("the workflow handoff must have exactly V010, V011, and V012 migration owners");
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
    "workflow.temporal-outcome-ambiguous",
    "workflow.existing-contract-mismatch",
  ],
);
const dispatchTransactions = requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/investigation/"
    + "workflow/InvestigationWorkflowDispatchTransactions.java",
  [
    "dispatcherTransactionManager",
    "opsmind_claim_investigation_workflow_start",
    "opsmind_preflight_investigation_workflow_start",
    "opsmind_settle_investigation_workflow_start",
    "opsmind_terminalize_unclaimed_ineligible_workflow_starts",
    "Workflow starter must not claim more than one lease per transaction.",
    "private <T> T inDispatcher",
  ],
);
for (const forbiddenGenericClaimPath of [
  "OutboxLeaseRepository",
  "OutboxDispatcherTenantContextSql",
  "claimBatchForEventType",
]) {
  if (dispatchTransactions.includes(forbiddenGenericClaimPath)) {
    errors.push(`workflow dispatcher retains generic claim path: ${forbiddenGenericClaimPath}`);
  }
}
requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/messaging/"
    + "TransactionalOutboxClaimer.java",
  ["opsmind_has_unpublished_outbox_predecessor"],
);
requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/investigation/"
    + "workflow/InvestigationWorkflowStartLeaseRowMapper.java",
  ["implements RowMapper<OutboxLease>", "payload_bytes", "payload_digest"],
);
requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/investigation/"
    + "workflow/TemporalTransportFailureClassifier.java",
  ["outcomeUncertain", "workflow.temporal-outcome-ambiguous"],
);
requireMarkers(
  "services/platform-api/src/main/java/ai/opsmind/platform/investigation/"
    + "workflow/InvestigationWorkflowStartDispatcher.java",
  [
    "outcomeUncertain",
    "reconciliationRequired",
    "ambiguousRetryAllowed",
    "workflow.temporal-outcome-ambiguous",
    "workflow.reconciliation-required",
    "verifyPayloadIntegrity",
    "workflow.event-payload-invalid",
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
  "migrate_to 12",
  "CutoverBlockExit=%s",
  "NonterminalOrphansAfterReconciliation=%s",
  "VersionEleven=%s",
  "VersionTwelve=%s",
  "WorkflowSettlementFunctionAfterEleven=%s",
  "WorkflowSettlementOwnerAfterEleven=%s",
  "WorkflowClaimFunctionAfterTwelve=%s",
  "WorkflowClaimOwnerAfterTwelve=%s",
  "WorkflowClaimSecurityDefinerAfterTwelve=%s",
  "OutboxPredecessorFunctionAfterTwelve=%s",
  "OutboxPredecessorOwnerAfterTwelve=%s",
  "OutboxPredecessorSecurityDefinerAfterTwelve=%s",
  "OutboxPredecessorDispatcherExecuteAfterTwelve=%s",
  "OutboxPredecessorPublicExecuteAfterTwelve=%s",
  "DispatcherWorkflowBindingPrivilegeAfterTwelve=%s",
  "DispatcherInboxPrivilegeAfterTwelve=%s",
  "DispatcherWorkflowExclusionPolicyAfterTwelve=%s",
  "LegacyWorkflowMarkerAfterTwelve=%s",
  "LegacyWorkflowPreflightAfterTwelve=%s",
  "LegacyWorkflowParkedAfterTwelve=%s",
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
    "dispatcherRoleCannotDirectlyMutateBindingOrInboxAndCannotSeeCanonicalOutbox",
    "genericClaimSkipsEarlierCanonicalWorkflowStart",
    "hiddenCanonicalPredecessorStillBlocksSameAggregateGenericClaim",
    "ambiguousTemporalOutcomeRetriesAndReconcilesWithinBudget",
    "finalAmbiguousTemporalAttemptParksWithoutASecondRpc",
    "legacyV011AmbiguityCannotTakeTerminalDeadlineBranchAfterV012",
    "expiredLeaseCannotPartiallyAcknowledgeBindingInboxOrOutbox",
    "permanentFailureRejectsBindingInboxAndOutboxInOneTransaction",
    "moreThanOneHundredUnrelatedReadyTenantsCannotStarveWorkflowStarts",
  ],
  ["services/platform-api/src/test/java/ai/opsmind/platform/investigation/"
    + "workflow/InvestigationWorkflowStartDispatcherTest.java"]: [
    "preflightTerminalDecisionRejectsWithoutInvokingWorkflowClient",
    "stalePreflightSkipsWorkflowClientAndLeaseMutation",
    "finalOutcomeUncertainAttemptParksForReconciliation",
    "ambiguousRetryWithinBudgetReachesDeterministicTemporalReconciliation",
    "corruptPayloadUsesWorkflowSettlementRejectWithoutCallingTemporal",
    "reconciliationRequiredParksUndecodablePayloadWithoutInvokingWorkflowClient",
    "exhaustedAmbiguousRetryParksUndecodablePayloadForReconciliation",
  ],
  ["services/platform-api/src/test/java/ai/opsmind/platform/investigation/"
    + "workflow/TemporalInvestigationWorkflowClientTest.java"]: [
    "matchingMemoCannotHideDifferentFirstStartInput",
    "rawRuntimeFailureDuringReconciliationRequiresReconciliation",
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
  ["services/platform-api/src/test/java/ai/opsmind/platform/"
    + "persistence/MigrationContractTest.java"]: [
    "workflowDispatchExclusivityMigrationGuardsBothMembershipDirections",
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
  `ExclusivityMigrationOwner=${path.basename(exclusivityMigrationPath)}`,
  `TemporalVersionPins=${temporalVersionDeclarations.length}`,
  `SerializedPayloadFields=${serializedFields.length}`,
  `RequiredTestFiles=${Object.keys(requiredTests).length}`,
  `Errors=${errors.length}`,
  `WorkflowHandoffResult=${errors.length === 0 ? "PASS" : "BLOCK"}`,
  ...errors.slice(0, 50).map((error) => `Error=${error}`),
];
process.stdout.write(`${lines.join("\n")}\n`);
process.exit(errors.length === 0 ? 0 : 1);
