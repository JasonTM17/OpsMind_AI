import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { createContractFileAccess } from
  "./phase-04-incident-contracts/safe-contract-files.mjs";
import {
  validateWorkerEnvironmentNames,
} from "./phase-09-temporal-worker-environment-contract.mjs";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "../..");
const failures = [];
const access = createContractFileAccess(repositoryRoot, failures);

function read(relativePath) {
  const absolutePath = path.join(repositoryRoot, relativePath);
  if (!fs.existsSync(absolutePath)) {
    failures.push(`missing required file: ${relativePath}`);
    return "";
  }
  return access.readSafeFile(absolutePath);
}

function requireMarkers(relativePath, markers) {
  const source = read(relativePath);
  for (const marker of markers) {
    if (!source.includes(marker)) {
      failures.push(`${relativePath} misses: ${marker}`);
    }
  }
  return source;
}

const workflowRoot = "services/platform-api/src/main/java/ai/opsmind/platform/"
  + "investigation/workflow/";
const workerBootstrapPath = "services/platform-api/src/main/java/ai/opsmind/"
  + "temporalworker/InvestigationTemporalWorkerBootstrapConfiguration.java";
requireMarkers("services/platform-api/src/main/java/ai/opsmind/platform/PlatformApiApplication.java", [
  "@SpringBootApplication",
  "package ai.opsmind.platform;",
]);
requireMarkers(`${workflowRoot}InvestigationWorkflow.java`, [
  "@WorkflowInterface",
  'String TYPE = "opsmind-investigation-v1"',
  "@WorkflowMethod(name = TYPE)",
]);
requireMarkers(`${workflowRoot}InvestigationWorkflowProperties.java`, [
  "!InvestigationWorkflow.TYPE.equals(workflowType)",
  "Temporal workflow type is outside policy.",
]);
requireMarkers(`${workflowRoot}InvestigationTemporalWorkerApplication.java`, [
  "WebApplicationType.NONE",
  "InvestigationTemporalWorkerBootstrapConfiguration.class",
]);
const workerBootstrap = requireMarkers(workerBootstrapPath, [
  "package ai.opsmind.temporalworker;",
  'prefix = "opsmind.investigation.temporal-worker"',
  "InvestigationTemporalWorkerRuntime.class",
]);
if (/^package ai\.opsmind\.platform(?:\.|;)/m.test(workerBootstrap)) {
  failures.push("worker bootstrap must remain outside the Platform API component scan");
}
if (fs.existsSync(path.join(
  repositoryRoot, `${workflowRoot}InvestigationTemporalWorkerConfiguration.java`,
))) {
  failures.push("legacy in-scan Temporal worker configuration must not exist");
}
requireMarkers(`${workflowRoot}InvestigationTemporalWorkerProperties.java`, [
  '@ConfigurationProperties(prefix = "opsmind.investigation.temporal-worker")',
  "maxConcurrentWorkflowTaskExecutors",
  "maxConcurrentWorkflowTaskPollers",
  "Temporal worker configuration is outside policy.",
]);
const runtime = requireMarkers(`${workflowRoot}InvestigationTemporalWorkerRuntime.java`, [
  "implements SmartLifecycle, AutoCloseable",
  "worker.registerWorkflowImplementationTypes(ParkedInvestigationWorkflow.class)",
  ".setStickyQueueScheduleToStartTimeout(Duration.ofSeconds(1))",
  "serviceStubs.shutdownNow()",
]);
if (runtime.includes("registerActivitiesImplementations")) {
  failures.push("Temporal worker must not register activities");
}
if (runtime.includes("setUseBuildIdForVersioning(true)")) {
  failures.push("Temporal worker must not enable server-side build-id routing");
}
requireMarkers(`${workflowRoot}ParkedInvestigationWorkflow.java`, [
  "CancellationScope.current().getCancellationRequest().get()",
  "CancellationScope.throwCanceled()",
  "Workflow start metadata is invalid.",
]);

const testRoot = "services/platform-api/src/test/java/ai/opsmind/platform/"
  + "investigation/workflow/";
requireMarkers(`${testRoot}InvestigationTemporalWorkerConfigurationTest.java`, [
  "workerIsAbsentByDefaultAndApplicationIsNonWeb",
  "enabledContextContainsOnlyWorkerTemporalInfrastructure",
  "doesNotHaveBean(InvestigationTemporalWorkerRuntime.class)",
]);
requireMarkers(`${testRoot}InvestigationTemporalWorkerPropertiesTest.java`, [
  "exactBoundedConfigurationIsAccepted",
  "defaultOffAndMismatchedIdentityBuildOrBoundsAreRejected",
]);
requireMarkers(`${testRoot}TemporalInvestigationWorkerReadinessProbeTest.java`, [
  "freshExactPollerOnWorkflowQueueIsReady",
  "incompatibleOrUnfreshPollerIsNotReady",
  "rpcFailurePropagatesForAdmissionToFailClosed",
]);
requireMarkers(`${testRoot}TemporalInvestigationWorkflowAdmissionTest.java`, [
  "nonCanonicalWorkflowTypeClosesAdmissionBeforeThePollerRpc",
  "verifyNoInteractions(probe)",
]);
const restartTest = requireMarkers(`${testRoot}InvestigationTemporalWorkerRestartTest.java`, [
  "OPSMIND_PHASE9_TEMPORAL_INTEGRATION",
  "parkedWorkflowReplaysOnRealTemporalAfterWorkerRestartAndCancelsWithoutLeaks",
  "awaitWorkflowTaskCount",
  "stub.cancel(\"phase-09-test-cleanup\")",
  "TemporalWorkflowHistoryCanaryAssertions.assertNoProhibitedContent(history)",
]);
if (restartTest.includes("TestWorkflowEnvironment")) {
  failures.push("restart proof must use the pinned local Temporal server, not TestWorkflowEnvironment");
}
requireMarkers(`${testRoot}TemporalWorkflowHistoryCanaryAssertions.java`, [
  "scanMessage(event",
  "restart-bearer-token-canary",
  "evidence_body",
  "capability_token",
]);
requireMarkers(`${testRoot}TemporalWorkflowHistoryCanaryAssertionsTest.java`, [
  "scannerRejectsCanariesInEveryPersistedHistoryLocation",
  "startedWithInput",
  "startedWithMemo",
  "startedWithHeader",
  "startedWithSearchAttribute",
  "canceledWithDetails",
]);
requireMarkers(`${testRoot}TemporalWorkerTestApplication.java`, [
  "InvestigationTemporalWorkerApplication.createApplication()",
  "getBeansOfType(DataSource.class)",
  "getBeansOfType(Flyway.class)",
]);

requireMarkers("services/platform-api/src/main/resources/application.yaml", [
  "required-worker-poller-max-age:",
  "required-worker-poller-future-skew:",
  "temporal-worker:",
  "enabled: ${OPSMIND_INVESTIGATION_TEMPORAL_WORKER_ENABLED:false}",
]);
requireMarkers(".env.example", [
  "OPSMIND_INVESTIGATION_TEMPORAL_WORKER_ENABLED=false",
  "OPSMIND_INVESTIGATION_TEMPORAL_WORKER_MAX_CONCURRENT_WORKFLOW_TASK_EXECUTORS=32",
  "OPSMIND_INVESTIGATION_TEMPORAL_WORKER_MAX_CONCURRENT_WORKFLOW_TASK_POLLERS=5",
]);
for (const launcher of ["scripts/dev/opsmind.ps1", "scripts/dev/opsmind.sh"]) {
  requireMarkers(launcher, [
    "OPSMIND_INVESTIGATION_TEMPORAL_WORKER_ENABLED",
    "OPSMIND_INVESTIGATION_TEMPORAL_WORKER_IDENTITY",
    "OPSMIND_INVESTIGATION_TEMPORAL_WORKER_BUILD_ID",
  ]);
}

const compose = read("compose.yaml");
const workerServiceStart = compose.search(/^  investigation-temporal-worker:\r?$/m);
const workerServiceTail = workerServiceStart < 0 ? "" : compose.slice(workerServiceStart);
const nextServiceOffset = workerServiceTail.search(
  /\r?\n {2}[A-Za-z][A-Za-z0-9_-]*:\r?\n/m,
);
const workerService = nextServiceOffset < 0
  ? workerServiceTail
  : workerServiceTail.slice(0, nextServiceOffset);
for (const marker of [
  "profiles: [phase-09-temporal-worker]",
  "InvestigationTemporalWorkerApplication",
  "PropertiesLauncher",
  "OPSMIND_INVESTIGATION_TEMPORAL_WORKER_ENABLED",
]) {
  if (!workerService.includes(marker)) {
    failures.push(`Compose worker service misses: ${marker}`);
  }
}
const workerEnvironment = workerService.match(
  /\r?\n {4}environment:\r?\n((?: {6}[^\r\n]+\r?\n)+)/,
)?.[1] ?? "";
const workerEnvironmentNames = [...workerEnvironment.matchAll(
  /^ {6}([A-Z][A-Z0-9_]*):/gm,
)].map((match) => match[1]);
for (const failure of validateWorkerEnvironmentNames(workerEnvironmentNames)) {
  failures.push(`Compose ${failure}`);
}
if (workerEnvironmentNames.some((name) =>
  /(?:^|_)(?:PASSWORD|SECRET|TOKEN|KEY)(?:_|$)|^(?:POSTGRES|SPRING|AI|DEEPSEEK|OIDC|TOOL|DATABASE|PG|REDIS|KAFKA|MINIO)_/.test(name)
)) {
  failures.push("Compose worker service must not receive application credentials");
}

const workflow = requireMarkers(".github/workflows/pr-quality.yml", [
  "validate-phase-09-temporal-worker-conformance.mjs",
  "phase-09-temporal-worker-environment-contract.test.mjs",
  "temporalio/temporal@sha256:2aeb97183876db2d80abc2e8b30c2157b5b7da00d53576e3eb40b972311db801",
  "server start-dev --ip 0.0.0.0",
  "OPSMIND_PHASE9_TEMPORAL_INTEGRATION=true",
  "InvestigationTemporalWorkerRestartTest",
  "temporal-development-server.log",
  "phase-09-temporal-worker-conformance",
]);
if (workflow.includes("temporalio/temporal:latest")) {
  failures.push("CI must not use a mutable Temporal latest tag");
}

if (failures.length > 0) {
  console.error("Phase 9 Temporal worker conformance validation failed:");
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exit(1);
}

console.log(
  "Phase 9 Temporal worker conformance passed: isolated process, bounded pollers, "
    + "real-server restart proof, and history leak fences.",
);
