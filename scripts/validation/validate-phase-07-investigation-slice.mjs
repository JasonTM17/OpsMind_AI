import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
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

const openApiPath = path.join(contractsRoot, "openapi", "opsmind-v1.yaml");
const openApi = access.readSafeFile(openApiPath);
for (const marker of [
  "operationId: startInvestigation",
  "operationId: getInvestigation",
  "../json-schema/investigation/v1/investigation-run.schema.json",
  "../json-schema/investigation/v1/investigation-view.schema.json",
]) {
  if (!openApi.includes(marker)) errors.push("OpenAPI investigation contract is incomplete");
}

const activityFieldNames = new Set([
  "eventId",
  "source",
  "eventType",
  "occurredAt",
  "actorId",
  "incidentVersion",
  "investigationRunId",
  "investigationSequence",
]);
const activityForbiddenColumns = [
  "payload",
  "reason",
  "operation_id",
  "external_trace_id",
  "terminal_reason",
  "final_response",
  "accepted_response",
  "canonical_content",
  "evidence_id",
  "evidence_ids_state",
  "pending_intents_state",
  "tool_request_digest",
  "credential",
  "prompt",
  "reasoning",
];

function sameMembers(actual, expected) {
  return actual.size === expected.size
    && [...actual].every((value) => expected.has(value));
}

const incidentSourceRoot = path.join(
  repositoryRoot, "services", "platform-api", "src", "main", "java",
  "ai", "opsmind", "platform", "incident",
);
const activityEntryPath = path.join(incidentSourceRoot, "IncidentActivityTimelineEntry.java");
const incidentQueryServicePath = path.join(incidentSourceRoot, "IncidentQueryService.java");
const incidentTimelineRepositoryPath = path.join(
  incidentSourceRoot, "JdbcIncidentTimelineRepository.java",
);
const activityQueryPath = path.join(
  incidentSourceRoot, "IncidentActivityTimelineQuery.java",
);
const activityIntegrationTestPath = path.join(
  repositoryRoot, "services", "platform-api", "src", "test", "java",
  "ai", "opsmind", "platform", "incident", "IncidentActivityTimelineHttpIntegrationTest.java",
);
const activitySchemaPath = path.join(
  contractsRoot, "json-schema", "incidents", "incident-activity-timeline-entry.schema.json",
);
const activityBridgePaths = [
  activityEntryPath,
  incidentQueryServicePath,
  incidentTimelineRepositoryPath,
  activityQueryPath,
  activityIntegrationTestPath,
  activitySchemaPath,
];
if (!activityBridgePaths.every(fs.existsSync)) {
  errors.push("incident activity timeline bridge files are incomplete");
}
else {
  const activityEntrySource = access.readSafeFile(activityEntryPath);
  const queryServiceSource = access.readSafeFile(incidentQueryServicePath);
  const repositorySource = access.readSafeFile(incidentTimelineRepositoryPath);
  const activityQuerySource = access.readSafeFile(activityQueryPath);
  const integrationTestSource = access.readSafeFile(activityIntegrationTestPath);
  let activitySchema;
  try {
    activitySchema = JSON.parse(access.readSafeFile(activitySchemaPath));
  }
  catch {
    errors.push("incident activity timeline schema is not valid JSON");
  }

  const recordHeader = activityEntrySource.match(
    /public record IncidentActivityTimelineEntry\(([\s\S]*?)\)\s*\{/u,
  )?.[1] ?? "";
  const recordFields = new Set([...recordHeader.matchAll(
    /\b(?:UUID|String|Instant|Long)\s+([a-z][A-Za-z0-9]*)/gu,
  )].map((match) => match[1]));
  const incidentSchemaFields = new Set(Object.keys(
    activitySchema?.$defs?.incidentEntry?.properties ?? {},
  ));
  const investigationSchemaFields = new Set(Object.keys(
    activitySchema?.$defs?.investigationEntry?.properties ?? {},
  ));
  const schemaFields = new Set([...incidentSchemaFields, ...investigationSchemaFields]);
  if (!sameMembers(recordFields, activityFieldNames)
      || !sameMembers(schemaFields, activityFieldNames)) {
    errors.push("incident activity timeline is not the exact eight-field bridge");
  }

  const serviceStart = queryServiceSource.indexOf("IncidentActivityTimelinePage activityTimeline(");
  const serviceEnd = queryServiceSource.indexOf("private int sourceRank", serviceStart);
  const serviceBridge = serviceStart >= 0 && serviceEnd > serviceStart
    ? queryServiceSource.slice(serviceStart, serviceEnd)
    : "";
  if (!serviceBridge.includes("IncidentScopePolicy.ANALYZE_SCOPE")
      || !serviceBridge.includes("IncidentAccessMode.ANALYZE")
      || serviceBridge.includes("IncidentAccessMode.READ")) {
    errors.push("incident activity timeline is not ANALYZE-only");
  }

  const repositoryStart = repositorySource.indexOf(
    "public List<IncidentActivityTimelineEntry> listActivity(",
  );
  const repositoryEnd = repositorySource.indexOf(
    "private IncidentActivityTimelineEntry mapActivityEvent", repositoryStart,
  );
  const repositoryMethod = repositoryStart >= 0 && repositoryEnd > repositoryStart
    ? repositorySource.slice(repositoryStart, repositoryEnd)
    : "";
  if (!repositoryMethod.includes("IncidentActivityTimelineQuery.build(")
      || !repositoryMethod.includes("query.sql()")
      || !repositoryMethod.includes("query.parameters().toArray()")) {
    errors.push("incident activity repository does not execute the shared production query");
  }
  const queryStart = activityQuerySource.indexOf("static Prepared build(");
  const queryEnd = activityQuerySource.indexOf("record Prepared(", queryStart);
  const repositoryBridge = queryStart >= 0 && queryEnd > queryStart
    ? activityQuerySource.slice(queryStart, queryEnd)
    : "";
  const scopedPredicate = "WHERE organization_id = ? AND project_id = ? AND incident_id = ?";
  const scopedPredicateCount = repositoryBridge.split(scopedPredicate).length - 1;
  for (const marker of [
    "UNION ALL",
    "AND (occurred_at, event_id) > (?, ?)",
    "AND occurred_at >= ?",
    "AND occurred_at > ?",
    "ORDER BY occurred_at ASC, source_rank ASC, event_id ASC LIMIT ?",
  ]) {
    if (!repositoryBridge.includes(marker)) {
      errors.push(`incident activity timeline query is missing: ${marker}`);
    }
  }
  const branchOrder = "ORDER BY occurred_at ASC, event_id ASC LIMIT ?";
  const firstBranchOrder = repositoryBridge.indexOf(branchOrder);
  const secondBranchOrder = repositoryBridge.indexOf(branchOrder, firstBranchOrder + 1);
  const union = repositoryBridge.indexOf("UNION ALL");
  if (
    firstBranchOrder < 0
    || secondBranchOrder < 0
    || repositoryBridge.indexOf(branchOrder, secondBranchOrder + 1) >= 0
    || firstBranchOrder > union
    || secondBranchOrder < union
  ) {
    errors.push("incident activity timeline query must bound each source before the union");
  }
  if (
    repositoryBridge.includes("(occurred_at, 0, event_id)")
    || repositoryBridge.includes("(occurred_at, 1, event_id)")
    || (repositoryBridge.match(/parameters\.add\(limit\)/gu) ?? []).length !== 3
  ) {
    errors.push("incident activity timeline cursor or branch limits are not index-bounded");
  }
  if (scopedPredicateCount !== 2) {
    errors.push("incident activity timeline query must scope both ledger branches exactly");
  }
  const projectedForbiddenColumns = activityForbiddenColumns.filter((column) =>
    new RegExp(`\\b${column}\\b`, "u").test(repositoryBridge));
  if (projectedForbiddenColumns.length > 0) {
    errors.push("incident activity timeline query reads a forbidden classified column");
  }

  for (const marker of [
    "application/vnd.opsmind.incident-activity-timeline.v1+json",
    "requiredScope: incident:analyze",
    "incidentAccessMode: ANALYZE",
  ]) {
    if (!openApi.includes(marker)) {
      errors.push(`incident activity timeline OpenAPI contract is missing: ${marker}`);
    }
  }
  for (const marker of [
    "secret-free-text-",
    "doesNotContain(sentinel, \"triage\")",
    "vendorRouteMaintainsHiddenDenialAcrossTenantProjectIncidentAndCursorScopes",
    "unionQueryExcludesSameTenantRowsFromOtherProjectsAndIncidents",
  ]) {
    if (!integrationTestSource.includes(marker)) {
      errors.push(`incident activity timeline boundary proof is missing: ${marker}`);
    }
  }
}

const sourceRoot = path.join(
  repositoryRoot, "services", "platform-api", "src", "main", "java",
  "ai", "opsmind", "platform", "investigation",
);
const sourceFiles = fs.readdirSync(sourceRoot, { recursive: true, withFileTypes: true })
  .filter((entry) => entry.isFile() && entry.name.endsWith(".java"))
  .map((entry) => path.join(entry.parentPath, entry.name));
const source = sourceFiles.map((file) => access.readSafeFile(file)).join("\n");
const domainSource = sourceFiles
  .filter((file) => file.includes(`${path.sep}domain${path.sep}`))
  .map((file) => access.readSafeFile(file))
  .join("\n");
for (const marker of [
  "InvestigationStateMachine",
  "The same tool intent was requested twice.",
  "Final analysis contains an unpersisted citation.",
  "@Profile(\"fixture\")",
]) {
  if (!source.includes(marker)) errors.push(`investigation invariant missing: ${marker}`);
}
if (/ProcessBuilder|Runtime\.getRuntime|JdbcTemplate|createStatement/iu.test(domainSource)
    || /chain[_-]?of[_-]?thought/iu.test(source)) {
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

const migrationPath = path.join(
  repositoryRoot,
  "services", "platform-api", "src", "main", "resources", "db", "migration",
  "V006__investigation_run_persistence.sql",
);
const durableStorePath = path.join(
  sourceRoot, "application", "JdbcInvestigationRunStore.java",
);
const durablePersistencePresent = fs.existsSync(migrationPath) && fs.existsSync(durableStorePath)
  && [
    "CREATE TABLE investigation_runs",
    "CREATE TABLE investigation_run_events",
    "ALTER TABLE investigation_runs FORCE ROW LEVEL SECURITY",
    "investigation-audit-v1",
    "opsmind_validate_investigation_event_count",
  ].every((marker) => access.readSafeFile(migrationPath).includes(marker));
if (!durablePersistencePresent) {
  errors.push("durable investigation persistence contract is incomplete");
}

const authorizedAiClientPath = path.join(
  sourceRoot, "integration", "AuthorizedInvestigationAiRuntimeClient.java",
);
const analysisBoundaryPath = path.join(
  sourceRoot, "integration", "InvestigationAnalysisBoundaryValidator.java",
);
const analysisPromptPath = path.join(
  sourceRoot, "integration", "InvestigationAnalysisPromptAssembler.java",
);
const analysisRequestPath = path.join(
  sourceRoot, "integration", "InvestigationAnalysisRequest.java",
);
const sliceConfigurationPath = path.join(
  sourceRoot, "application", "InvestigationSliceConfiguration.java",
);
const capabilityBackedAiFiles = [
  authorizedAiClientPath,
  analysisBoundaryPath,
  analysisPromptPath,
  analysisRequestPath,
  sliceConfigurationPath,
];
const capabilityBackedAiMarkers = [
  [authorizedAiClientPath, "authorizer.requireEvidenceRecords("],
  [authorizedAiClientPath, "tokenIssuer.issue(new AnalysisCapabilityGrant("],
  [authorizedAiClientPath, "runtimeClient.analyze("],
  [authorizedAiClientPath, "catalog.publicSelectors()"],
  [authorizedAiClientPath, "roundDeadline(request.deadlineAt())"],
  [analysisBoundaryPath, "response.requestedToolCalls().forEach(catalog::resolve)"],
  [analysisBoundaryPath, "allowedCitations.get(citation.evidenceId())"],
  [analysisPromptPath, "Do not invent evidence, PromQL"],
  [analysisRequestPath, "int remainingTokens"],
  [analysisRequestPath, "int remainingToolCalls"],
  [sliceConfigurationPath, "@Profile(\"!fixture\")"],
  [sliceConfigurationPath, "new AuthorizedInvestigationAiRuntimeClient("],
  [sliceConfigurationPath, "capabilityProperties.maximumLifetime()"],
];
const capabilityBackedAiPresent = capabilityBackedAiFiles.every(fs.existsSync)
  && capabilityBackedAiMarkers.every(([file, marker]) =>
    access.readSafeFile(file).includes(marker));
if (!capabilityBackedAiPresent) {
  errors.push("capability-backed investigation AI client contract is incomplete");
}

const toolClientPath = path.join(
  sourceRoot, "integration", "HttpInvestigationToolGatewayClient.java",
);
const toolRequestPath = path.join(
  sourceRoot, "integration", "ToolGatewayRequestCanonicalizer.java",
);
const toolResponsePath = path.join(
  sourceRoot, "integration", "ToolGatewayResponseValidator.java",
);
const toolTransportPath = path.join(
  sourceRoot, "integration", "DeadlineBoundedToolGatewayHttpExchange.java",
);
const toolFixturePath = path.join(
  repositoryRoot, "packages", "contracts", "fixtures", "tool-gateway",
  "investigation-tool-execution-request-v1.canonical.json",
);
const capabilityBackedToolFiles = [
  toolClientPath,
  toolRequestPath,
  toolResponsePath,
  toolTransportPath,
  toolFixturePath,
];
const capabilityBackedToolMarkers = [
  [toolClientPath, "workloadTokens.accessToken()"],
  [toolClientPath, "capabilityTokens.issue(new ToolCapabilityGrant("],
  [toolClientPath, ".header(\"Authorization\", \"Bearer \" + workloadToken)"],
  [toolClientPath, ".header(CAPABILITY_HEADER, capabilityToken)"],
  [toolRequestPath, "InvestigationToolInvocation invocation = catalog.resolve(intent)"],
  [toolRequestPath, "RequestDigest.sha256(body)"],
  [toolResponsePath, "evidenceCanonicalizer.canonicalize(evidence.content())"],
  [toolResponsePath, "request.invocation().expectedManifestVersion()"],
  [toolTransportPath, "httpClient.sendAsync("],
  [sliceConfigurationPath, "new HttpInvestigationToolGatewayClient("],
];
const capabilityBackedToolPresent = capabilityBackedToolFiles.every(fs.existsSync)
  && capabilityBackedToolMarkers.every(([file, marker]) =>
    access.readSafeFile(file).includes(marker));
if (!capabilityBackedToolPresent) {
  errors.push("dual-credential investigation Tool Gateway client contract is incomplete");
}

const gatewayRoot = path.join(
  repositoryRoot, "services", "tool-gateway", "src", "main",
);
const gatewaySourceRoot = path.join(gatewayRoot, "java", "ai", "opsmind", "toolgateway");
const gatewayMigrationPath = path.join(
  gatewayRoot, "resources", "db", "migration", "V001__durable_tool_gateway_state.sql",
);
const gatewayTenantIsolationMigrationPath = path.join(
  gatewayRoot, "resources", "db", "migration", "V003__tenant_project_row_security.sql",
);
const receiptStorePath = path.join(
  gatewaySourceRoot, "persistence", "JdbcExecutionReceiptStore.java",
);
const transactionRunnerPath = path.join(
  gatewaySourceRoot, "persistence", "JdbcToolExecutionTransactionRunner.java",
);
const isolationReadinessPath = path.join(
  gatewaySourceRoot, "persistence", "GatewayIsolationReadinessSql.java",
);
const persistencePropertiesPath = path.join(
  gatewaySourceRoot, "persistence", "GatewayPersistenceProperties.java",
);
const gatewayPersistenceFiles = [
  gatewayMigrationPath,
  gatewayTenantIsolationMigrationPath,
  receiptStorePath,
  transactionRunnerPath,
  isolationReadinessPath,
  persistencePropertiesPath,
  path.join(gatewaySourceRoot, "persistence", "JdbcNonceReplayStore.java"),
  path.join(gatewaySourceRoot, "persistence", "JdbcToolAuditWriter.java"),
];
const durableGatewayMarkers = [
  [gatewayMigrationPath, "CREATE TABLE tool_gateway.capability_nonce_claims"],
  [gatewayMigrationPath, "CREATE TABLE tool_gateway.execution_receipts"],
  [gatewayMigrationPath, "tool_audit_events_reject_truncate"],
  [gatewayTenantIsolationMigrationPath, "FORCE ROW LEVEL SECURITY"],
  [gatewayTenantIsolationMigrationPath, "unverified_tool_audit_events"],
  [receiptStorePath, "lease_token = ?"],
  [receiptStorePath, "validateCompletedResponse("],
  [receiptStorePath, "completionMarginMilliseconds"],
  [receiptStorePath, "LEAST(? + (? * INTERVAL '1 millisecond'),"],
  [transactionRunnerPath, "tenantContext.apply(scope)"],
  [isolationReadinessPath, "has_schema_privilege(current_user, 'tool_gateway', 'USAGE')"],
  [isolationReadinessPath, "EXPECTED_POLICY_EXPRESSION"],
  [persistencePropertiesPath, "LEASE_COMPLETION_MARGIN"],
];
const durableGatewayPresent = gatewayPersistenceFiles.every(fs.existsSync)
  && durableGatewayMarkers.every(([file, marker]) =>
    access.readSafeFile(file).includes(marker));
if (!durableGatewayPresent) {
  errors.push("durable Tool Gateway persistence contract is incomplete");
}

const prometheusRoot = path.join(
  gatewaySourceRoot, "connectors", "prometheus",
);
const prometheusConnectorPath = path.join(
  prometheusRoot, "PrometheusObservabilityConnector.java",
);
const prometheusExchangePath = path.join(
  prometheusRoot, "PrometheusHttpExchange.java",
);
const prometheusHttpFactoryPath = path.join(
  prometheusRoot, "PrometheusHttpClientFactory.java",
);
const prometheusParserPath = path.join(
  prometheusRoot, "PrometheusResponseParser.java",
);
const prometheusQueryPath = path.join(
  prometheusRoot, "PrometheusQueryCatalog.java",
);
const composePath = path.join(repositoryRoot, "compose.yaml");
const workflowPath = path.join(repositoryRoot, ".github", "workflows", "pr-quality.yml");
const prometheusFiles = [
  prometheusConnectorPath,
  prometheusExchangePath,
  prometheusHttpFactoryPath,
  prometheusParserPath,
  prometheusQueryPath,
  path.join(
    gatewayRoot, "resources", "tool-manifests",
    "observability-metrics-query-prometheus-v1.json",
  ),
  path.join(repositoryRoot, "deploy", "prometheus", "prometheus.yml"),
  path.join(repositoryRoot, "deploy", "prometheus", "opsmind-recording-rules.yml"),
  path.join(
    repositoryRoot, "scripts", "validation",
    "validate-live-prometheus-response.mjs",
  ),
];
const prometheusMarkers = [
  [prometheusConnectorPath, "\"source-attested\""],
  [prometheusExchangePath, "sendAsync("],
  [prometheusHttpFactoryPath, "HttpClient.Redirect.NEVER"],
  [prometheusParserPath, "\"matrix\".equals("],
  [prometheusQueryPath, "opsmind:http_request_duration_seconds:synthetic"],
  [composePath, "prom/prometheus:v3.12.0-distroless@sha256:"],
  [workflowPath, "validate-live-prometheus-response.mjs"],
];
const livePrometheusPresent = prometheusFiles.every(fs.existsSync)
  && prometheusMarkers.every(([file, marker]) =>
    access.readSafeFile(file).includes(marker));
if (!livePrometheusPresent) {
  errors.push("bounded live Prometheus connector contract is incomplete");
}

const operatorRoot = path.join(repositoryRoot, "apps", "operator-web");
const operatorRoutePath = path.join(
  operatorRoot, "app", "organizations", "[organizationId]", "projects", "[projectId]",
  "incidents", "[incidentId]", "investigations", "[runId]", "page.tsx",
);
const operatorSessionPath = path.join(
  operatorRoot, "lib", "platform-api", "operator-session.ts",
);
const operatorTransportPath = path.join(
  operatorRoot, "lib", "platform-api", "bounded-platform-fetch.ts",
);
const platformOperatorProjectionPath = path.join(
  repositoryRoot, "services", "platform-api", "src", "main", "java",
  "ai", "opsmind", "platform", "common", "api", "OperatorProjection.java",
);
const platformDisplayRedactorPath = path.join(
  repositoryRoot, "services", "platform-api", "src", "main", "java",
  "ai", "opsmind", "platform", "common", "api", "OperatorDisplayRedactor.java",
);
const incidentOperatorProjectionPath = path.join(
  repositoryRoot, "services", "platform-api", "src", "main", "java",
  "ai", "opsmind", "platform", "incident", "OperatorIncidentProjection.java",
);
const investigationOperatorProjectionPath = path.join(
  sourceRoot, "projection", "OperatorInvestigationProjection.java",
);
const investigationReadServicePath = path.join(
  sourceRoot, "application", "InvestigationRunService.java",
);
const investigationRunReaderPath = path.join(
  sourceRoot, "application", "JdbcInvestigationRunReader.java",
);
const investigationOperatorHttpTestPath = path.join(
  repositoryRoot, "services", "platform-api", "src", "test", "java",
  "ai", "opsmind", "platform", "investigation", "api",
  "InvestigationRunControllerHttpTest.java",
);
const operatorLoaderPath = path.join(
  operatorRoot, "lib", "platform-api", "load-investigation-workspace.ts",
);
const operatorParserPath = path.join(
  operatorRoot, "features", "investigation", "parse-investigation.ts",
);
const operatorWorkspacePath = path.join(
  operatorRoot, "features", "investigation", "investigation-workspace.tsx",
);
const evidenceSpinePath = path.join(
  operatorRoot, "features", "investigation", "evidence-spine.tsx",
);
const browserTestPath = path.join(
  operatorRoot, "tests", "e2e", "investigation-workspace.spec.ts",
);
const browserBoundaryTestPath = path.join(
  operatorRoot, "tests", "e2e", "investigation-boundaries.spec.ts",
);
const operatorManifestPath = path.join(operatorRoot, "package.json");
const productionBrowserConfigPath = path.join(
  operatorRoot, "playwright.production.config.ts",
);
const productionBrowserTestPath = path.join(
  operatorRoot, "tests", "e2e-production", "fail-closed-production.spec.ts",
);
const productionLauncherPath = path.join(
  operatorRoot, "tests", "support", "start-production-smoke-stack.mjs",
);
const stitchReferencePath = path.join(
  repositoryRoot, "plans", "260723-0021-phase-07-capability-backed-investigation",
  "stitch", "operator-investigation-workspace-v2", "design.html",
);
const crossServiceRunnerPath = path.join(
  repositoryRoot, "scripts", "validation", "cross-service",
  "run-investigation-slice.mjs",
);
const fixtureProviderPath = path.join(
  repositoryRoot, "scripts", "validation", "cross-service",
  "fixture-provider.py",
);
const crossServiceHarnessPath = path.join(
  repositoryRoot, "scripts", "validation", "cross-service",
  "run-cross-service-verification.ps1",
);
const crossServiceSupportPath = path.join(
  repositoryRoot, "scripts", "validation", "cross-service",
  "cross-service-harness-support.ps1",
);
const crossServiceCleanupPath = path.join(
  repositoryRoot, "scripts", "validation", "cross-service",
  "cleanup-cross-service-run.ps1",
);
const crossServiceFinalizePath = path.join(
  repositoryRoot, "scripts", "validation", "cross-service",
  "finalize-cross-service-report.mjs",
);
const fixtureIdentityPath = path.join(
  repositoryRoot, "scripts", "validation", "cross-service",
  "fixture-identity.mjs",
);
const fixturePrometheusPath = path.join(
  repositoryRoot, "scripts", "validation", "cross-service",
  "fixture-prometheus.mjs",
);
const aiRuntimeLauncherPath = path.join(
  repositoryRoot, "scripts", "validation", "cross-service",
  "run-ai-runtime.py",
);
const fixtureIdentityProbePath = path.join(
  repositoryRoot, "scripts", "validation", "cross-service",
  "probe-fixture-identity.mjs",
);
const operatorFiles = [
  operatorRoutePath,
  operatorSessionPath,
  operatorTransportPath,
  platformOperatorProjectionPath,
  platformDisplayRedactorPath,
  incidentOperatorProjectionPath,
  investigationOperatorProjectionPath,
  investigationReadServicePath,
  investigationRunReaderPath,
  investigationOperatorHttpTestPath,
  operatorLoaderPath,
  operatorParserPath,
  operatorWorkspacePath,
  evidenceSpinePath,
  browserTestPath,
  browserBoundaryTestPath,
  operatorManifestPath,
  productionBrowserConfigPath,
  productionBrowserTestPath,
  productionLauncherPath,
  stitchReferencePath,
  crossServiceRunnerPath,
  fixtureProviderPath,
  crossServiceHarnessPath,
  crossServiceSupportPath,
  crossServiceCleanupPath,
  crossServiceFinalizePath,
  fixtureIdentityPath,
  fixturePrometheusPath,
  aiRuntimeLauncherPath,
  fixtureIdentityProbePath,
];
const operatorMarkers = [
  [operatorRoutePath, "loadInvestigationWorkspace(await params)"],
  [operatorSessionPath, "process.env.NODE_ENV === \"production\""],
  [operatorTransportPath, "MAXIMUM_RESPONSE_BYTES"],
  [operatorTransportPath, "redirect: \"error\""],
  [operatorTransportPath, "application/vnd.opsmind.operator-projection.v1+json"],
  [operatorTransportPath, "operator-browser-safe-v1"],
  [operatorTransportPath, "display-redaction-v1"],
  [platformOperatorProjectionPath, "application/vnd.opsmind.operator-projection.v1+json"],
  [platformOperatorProjectionPath, "X-OpsMind-Redaction-Count"],
  [platformOperatorProjectionPath, "vendorQuality >= jsonQuality"],
  [platformDisplayRedactorPath, "Normalizer.Form.NFKC"],
  [platformDisplayRedactorPath, "[REDACTED_EXECUTABLE_TEXT]"],
  [incidentOperatorProjectionPath, "OperatorDisplayRedactor"],
  [investigationOperatorProjectionPath, "Model-authored explanation withheld"],
  [investigationOperatorProjectionPath, "Investigation tool intent is not display-approved."],
  [investigationReadServicePath, "authorizer.requireReadAccess("],
  [investigationReadServicePath, "store.requireScoped("],
  [durableStorePath, "AND project_id = ? AND incident_id = ? AND run_id = ?"],
  [investigationOperatorHttpTestPath, "vendorRepresentationWithholdsModelProseAndEmitsAssurance"],
  [openApiPath, "application/vnd.opsmind.operator-projection.v1+json"],
  [operatorLoaderPath, "verifyIdentity(identity, incident, investigation)"],
  [operatorLoaderPath, "requestGroup.abort()"],
  [operatorLoaderPath, "projectionSafety"],
  [operatorParserPath, "completed analysis cites unpersisted evidence"],
  [operatorWorkspacePath, "<EvidenceSpine investigation={investigation} />"],
  [evidenceSpinePath, "investigation.pendingToolCalls.map"],
  [evidenceSpinePath, "Reviewed catalog label; executable arguments remain server-side."],
  [browserTestPath, "new AxeBuilder({ page })"],
  [browserTestPath, "width: 375"],
  [browserTestPath, "rejects an analysis projection containing raw reasoning fields"],
  [browserBoundaryTestPath, "missing browser-safe classification"],
  [browserBoundaryTestPath, "unknown catalog operation"],
  [operatorManifestPath, "\"test:e2e:production\""],
  [productionBrowserConfigPath, "testDir: \"./tests/e2e-production\""],
  [productionBrowserConfigPath, "start-production-smoke-stack.mjs"],
  [productionBrowserTestPath, "Secure operator session unavailable"],
  [productionLauncherPath, "\".next\", \"standalone\""],
  [productionLauncherPath, "\"server.js\""],
  [workflowPath, "playwright install --with-deps chromium"],
  [workflowPath, "pnpm --filter @opsmind/operator-web test:e2e:production"],
  [workflowPath, "pnpm --filter @opsmind/operator-web test:e2e"],
  [crossServiceRunnerPath, "CrossServiceTrace=PASS"],
  [crossServiceRunnerPath, "OPSMIND_WARM_RUNS"],
  [fixtureProviderPath, "opsmind_probe"],
  [fixtureProviderPath, "Synthetic Prometheus evidence"],
  [crossServiceHarnessPath, "CrossServiceVerification=PASS"],
  [crossServiceHarnessPath, "RemoveRunDirectory"],
  [crossServiceSupportPath, "Get-CrossServiceAvailablePorts"],
  [crossServiceCleanupPath, "CrossServiceRunCleanup=PASS"],
  [crossServiceCleanupPath, "tagPattern"],
  [crossServiceFinalizePath, "CrossServiceDurableState=PASS"],
  [fixtureIdentityPath, "cross-service-identity-v1"],
  [fixturePrometheusPath, "opsmind:http_request_duration_seconds:synthetic"],
  [aiRuntimeLauncherPath, "AI Runtime"],
  [fixtureIdentityProbePath, "FixtureIdentityProbe=PASS"],
];
const missingOperatorFiles = operatorFiles.filter((file) => !fs.existsSync(file));
const missingOperatorMarkers = operatorMarkers.filter(([file, marker]) =>
  !access.readSafeFile(file).includes(marker));
const operatorWorkspacePresent =
  missingOperatorFiles.length === 0 && missingOperatorMarkers.length === 0;
if (!operatorWorkspacePresent) {
  errors.push("CK/Stitch operator investigation workspace contract is incomplete");
  errors.push(...missingOperatorFiles.map((file) =>
    `operator workspace file is missing: ${access.relativeName(file)}`));
  errors.push(...missingOperatorMarkers.map(([file, marker]) =>
    `operator workspace marker is missing: ${access.relativeName(file)} :: ${marker}`));
}

const crossServiceReportPath = path.join(
  repositoryRoot, ".opsmind", "reports", "cross-service-trace.json",
);
function validateCrossServiceReport() {
  if (!fs.existsSync(crossServiceReportPath)) {
    return ["cross-service trace report is missing"];
  }
  let report;
  try {
    report = JSON.parse(fs.readFileSync(crossServiceReportPath, "utf8"));
  } catch {
    return ["cross-service trace report is not valid JSON"];
  }
  const reportErrors = [];
  const warmRuns = report.warmRuns;
  if (report.schema !== "opsmind-cross-service-trace-v1") {
    reportErrors.push("cross-service report schema is invalid");
  }
  if (!Number.isInteger(warmRuns) || warmRuns < 100) {
    reportErrors.push("cross-service report must contain at least 100 warm runs");
  }
  if (report.terminalStatus !== "PASS") {
    reportErrors.push("cross-service report is not terminal PASS");
  }
  const latency = report.latencyMs ?? {};
  if (
    !Number.isFinite(latency.p95)
    || !Number.isFinite(latency.threshold)
    || latency.thresholdPass !== true
    || latency.p95 > latency.threshold
  ) {
    reportErrors.push("cross-service p95 evidence is missing or exceeds its threshold");
  }
  const provider = report.fixtureObservations?.provider ?? {};
  const prometheus = report.fixtureObservations?.prometheus ?? {};
  if (
    provider.probe_requests !== 1
    || provider.analysis_requests !== warmRuns * 2
    || prometheus.query_requests !== warmRuns
  ) {
    reportErrors.push("cross-service fixture observations do not match warm runs");
  }
  const durable = report.durableState ?? {};
  if (
    durable.investigationRuns !== warmRuns
    || durable.evidenceRecords !== warmRuns
    || durable.analysisInvocations !== warmRuns * 2
    || durable.toolReceipts !== warmRuns
    || durable.toolAuditEvents < warmRuns
  ) {
    reportErrors.push("cross-service durable counts do not match warm runs");
  }
  if (
    !Array.isArray(report.runs)
    || report.runs.length !== warmRuns
    || report.runs.some((run) => run?.status !== "COMPLETED")
  ) {
    reportErrors.push("cross-service run samples are incomplete or non-terminal");
  } else {
    const runIds = report.runs.map((run) => run.runId);
    if (
      runIds.some((runId) => typeof runId !== "string" || runId.length === 0)
      || new Set(runIds).size !== runIds.length
      || report.runs.some((run) =>
        !Array.isArray(run.evidenceIds) || run.evidenceIds.length === 0)
    ) {
      reportErrors.push("cross-service run samples do not have unique IDs and evidence");
    }
  }
  let currentHead = "";
  try {
    currentHead = execFileSync("git", ["rev-parse", "HEAD"], {
      cwd: repositoryRoot,
      encoding: "utf8",
    }).trim();
  } catch {
    reportErrors.push("unable to resolve current git revision for cross-service evidence");
  }
  if (
    report.source?.workingTreeClean !== true
    || !currentHead
    || report.source?.gitHead !== currentHead
  ) {
    reportErrors.push("cross-service report is not bound to the clean current revision");
  }
  return reportErrors;
}
const crossServiceReportErrors = validateCrossServiceReport();
const blockers = [
  ...(!operatorWorkspacePresent
    ? ["CK/Stitch operator investigation UI and browser E2E proof are absent"]
    : []),
  ...(crossServiceReportErrors.length > 0
    ? crossServiceReportErrors
    : []),
];
const phaseExitPass =
  errors.length === 0 && crossServiceReportErrors.length === 0;
const lines = [
  "OpsMind Phase 7 investigation slice validation",
  "ValidationScope=OPERATOR_WORKSPACE_CHECKPOINT",
  `JsonSchemasParsed=${schemaFiles.length}`,
  `JsonFixturesParsed=${fixtureFiles.length}`,
  `LocalReferencesResolved=${referenceCount}`,
  `SourceFilesChecked=${sourceFiles.length}`,
  `OperatorWorkspace=${operatorWorkspacePresent ? "PASS" : "BLOCK"}`,
  `CrossServiceReport=${crossServiceReportErrors.length === 0 ? "PASS" : "BLOCK"}`,
  `CrossServiceReportErrors=${crossServiceReportErrors.length}`,
  `Errors=${errors.length}`,
  `CheckpointResult=${errors.length === 0 ? "PASS" : "BLOCK"}`,
  `PhaseExit=${phaseExitPass ? "PASS" : "BLOCK"}`,
  ...blockers.map((blocker) => `PhaseExitBlocker=${blocker}`),
  ...crossServiceReportErrors.slice(0, 50).map((error) => `CrossServiceReportError=${error}`),
  ...errors.slice(0, 50).map((error) => `Error=${error}`),
];
process.stdout.write(`${lines.join("\n")}\n`);
process.exit(errors.length === 0 && crossServiceReportErrors.length === 0 ? 0 : 1);
