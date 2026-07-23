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
const receiptStorePath = path.join(
  gatewaySourceRoot, "persistence", "JdbcExecutionReceiptStore.java",
);
const transactionRunnerPath = path.join(
  gatewaySourceRoot, "persistence", "JdbcToolExecutionTransactionRunner.java",
);
const gatewayPersistenceFiles = [
  gatewayMigrationPath,
  receiptStorePath,
  transactionRunnerPath,
  path.join(gatewaySourceRoot, "persistence", "JdbcNonceReplayStore.java"),
  path.join(gatewaySourceRoot, "persistence", "JdbcToolAuditWriter.java"),
];
const durableGatewayMarkers = [
  [gatewayMigrationPath, "CREATE TABLE tool_gateway.capability_nonce_claims"],
  [gatewayMigrationPath, "CREATE TABLE tool_gateway.execution_receipts"],
  [gatewayMigrationPath, "tool_audit_events_reject_truncate"],
  [receiptStorePath, "lease_token = ?"],
  [receiptStorePath, "validateCompletedResponse("],
  [transactionRunnerPath, "transactions.execute("],
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

const blockers = [
  "CK/Stitch operator investigation UI and browser E2E proof are absent",
  "cross-service trace and p95 benchmark evidence are absent",
];
const lines = [
  "OpsMind Phase 7 investigation slice validation",
  "ValidationScope=DURABLE_GATEWAY_PROMETHEUS_CHECKPOINT",
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
