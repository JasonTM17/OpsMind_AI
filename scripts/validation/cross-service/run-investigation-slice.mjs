import { performance } from "node:perf_hooks";
import { randomUUID } from "node:crypto";
import path from "node:path";
import {
  prepareValidationEvidence,
  publishValidationEvidence,
} from "../safe-validation-evidence.mjs";

const required = [
  "OPSMIND_PLATFORM_BASE_URL",
  "OPSMIND_ACCESS_TOKEN",
  "OPSMIND_ORGANIZATION_ID",
  "OPSMIND_PROJECT_ID",
  "OPSMIND_INCIDENT_ID",
  "OPSMIND_IDENTITY_STATUS_URL",
  "OPSMIND_PROVIDER_STATUS_URL",
  "OPSMIND_PROMETHEUS_STATUS_URL",
];
for (const name of required) {
  if (!process.env[name]) throw new Error(`${name} is required; credentials stay outside artifacts`);
}

const baseUrl = process.env.OPSMIND_PLATFORM_BASE_URL.replace(/\/+$/, "");
const scenario = process.env.OPSMIND_CROSS_SERVICE_SCENARIO ?? "A";
const scenarioContract = {
  A: {
    terminalStatus: "COMPLETED",
    analysisRequestsPerRun: 2,
    queryRequestsPerRun: 1,
    evidencePerRun: 1,
    toolCallsPerRun: 1,
  },
  B: {
    terminalStatus: "ABSTAINED",
    analysisRequestsPerRun: 1,
    queryRequestsPerRun: 0,
    evidencePerRun: 0,
    toolCallsPerRun: 0,
  },
  C: {
    terminalStatus: "COMPLETED",
    analysisRequestsPerRun: 2,
    queryRequestsPerRun: 2,
    evidencePerRun: 2,
    toolCallsPerRun: 2,
  },
}[scenario];
if (!scenarioContract) {
  throw new Error("OPSMIND_CROSS_SERVICE_SCENARIO must be one of A, B, or C");
}
const warmRuns = Number.parseInt(process.env.OPSMIND_WARM_RUNS ?? "100", 10);
if (!Number.isInteger(warmRuns) || warmRuns < 1 || warmRuns > 1000) {
  throw new Error("OPSMIND_WARM_RUNS must be between 1 and 1000");
}
const ids = {
  organization: process.env.OPSMIND_ORGANIZATION_ID,
  project: process.env.OPSMIND_PROJECT_ID,
  incident: process.env.OPSMIND_INCIDENT_ID,
};
const token = process.env.OPSMIND_ACCESS_TOKEN;
const endpoint = `${baseUrl}/api/v1/organizations/${ids.organization}/projects/${ids.project}/incidents/${ids.incident}/investigations`;
const forbidden = /(?:authorization\s*[:=]|bearer\s+|api[_-]?key\s*[:=]|password\s*[:=]|private[_-]?key|["']prompt["']\s*:|reasoning_content|(?:promql|sql)\s*[:=]|\bquery\s*=\s*)/i;

function exactLoopbackStatusUrl(name, protocol = "http:") {
  const url = new URL(process.env[name]);
  if (
    url.protocol !== protocol
    || !["127.0.0.1", "localhost"].includes(url.hostname)
    || url.username
    || url.password
    || url.search
    || url.hash
    || url.pathname !== "/__opsmind/status"
  ) {
    throw new Error(`${name} must be an exact loopback status endpoint`);
  }
  return url;
}

const providerStatusUrl = exactLoopbackStatusUrl("OPSMIND_PROVIDER_STATUS_URL");
const prometheusStatusUrl = exactLoopbackStatusUrl("OPSMIND_PROMETHEUS_STATUS_URL");
const identityStatusUrl = exactLoopbackStatusUrl("OPSMIND_IDENTITY_STATUS_URL", "https:");

async function request(url, init) {
  const started = performance.now();
  const response = await fetch(url, {
    ...init,
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/json",
      "Content-Type": "application/json",
      ...(init?.headers ?? {}),
    },
  });
  const elapsedMs = performance.now() - started;
  const body = await response.text();
  let parsed;
  try {
    parsed = body ? JSON.parse(body) : null;
  } catch {
    throw new Error(`Platform returned non-JSON status ${response.status}`);
  }
  return { response, body, parsed, elapsedMs };
}

async function status(url, schema, allowedFields) {
  const response = await fetch(url, {
    headers: { Accept: "application/json" },
    redirect: "error",
    signal: AbortSignal.timeout(5_000),
  });
  if (!response.ok) throw new Error(`fixture status failed with HTTP ${response.status}`);
  const text = await response.text();
  if (Buffer.byteLength(text) > 4_096 || forbidden.test(text)) {
    throw new Error("fixture status response is oversized or unsafe");
  }
  const document = JSON.parse(text);
  if (
    !document
    || document.schema !== schema
    || Object.keys(document).some((field) => !allowedFields.has(field))
  ) {
    throw new Error("fixture status response contract is invalid");
  }
  return document;
}

function startPayload(runId) {
  return {
    run_id: runId,
    max_rounds: 5,
    max_tool_calls: 4,
    max_evidence_items: 20,
    max_tokens: 4000,
    deadline_at: new Date(Date.now() + 120_000).toISOString(),
  };
}

function assertTerminal(result) {
  if (result.response.status !== 200) {
    throw new Error(`investigation start failed with HTTP ${result.response.status}`);
  }
  if (!result.parsed || result.parsed.status !== scenarioContract.terminalStatus) {
    const status = result.parsed?.status ?? "missing";
    const reason = typeof result.parsed?.terminalReason === "string"
      ? result.parsed.terminalReason
      : "missing";
    throw new Error(
      `investigation terminal mismatch; scenario=${scenario} status=${status} `
        + `expected=${scenarioContract.terminalStatus} terminalReason=${reason}`,
    );
  }
  if (
    !Array.isArray(result.parsed.evidenceIds)
    || result.parsed.evidenceIds.length !== scenarioContract.evidencePerRun
  ) {
    throw new Error("terminal investigation evidence count does not match the scenario");
  }
  if (forbidden.test(result.body)) throw new Error("cross-service response contains prohibited material");
}

async function runOne() {
  const runId = randomUUID();
  const started = await request(endpoint, {
    method: "POST",
    body: JSON.stringify(startPayload(runId)),
  });
  try {
    assertTerminal(started);
  } catch (error) {
    const [identity, provider, prometheus] = await Promise.all([
      status(
        identityStatusUrl,
        "opsmind-fixture-identity-status-v1",
        new Set([
          "schema",
          "runner_tokens",
          "workload_tokens",
          "identity_jwks_requests",
          "capability_jwks_requests",
        ]),
      ),
      status(
        providerStatusUrl,
        "opsmind-fixture-provider-status-v1",
          new Set(["schema", "scenario", "probe_requests", "analysis_requests", "total_requests"]),
      ),
      status(
        prometheusStatusUrl,
        "opsmind-fixture-prometheus-status-v1",
        new Set(["schema", "query_requests", "ready_requests"]),
      ),
    ]);
    throw new Error(
      `${error.message}; fixtureCounts=workloadTokens:${identity.workload_tokens},`
        + `identityJwks:${identity.identity_jwks_requests},`
        + `capabilityJwks:${identity.capability_jwks_requests},`
        + `providerAnalyses:${provider.analysis_requests},prometheusQueries:${prometheus.query_requests}`,
    );
  }
  const read = await request(`${endpoint}/${runId}`, {
    headers: { Accept: "application/vnd.opsmind.operator-projection.v1+json" },
  });
  if (read.response.status !== 200) throw new Error(`operator projection read failed with HTTP ${read.response.status}`);
  if (
    !read.parsed
    || typeof read.parsed !== "object"
    || read.parsed.runId !== runId
    || read.parsed.status !== started.parsed.status
  ) {
    throw new Error("operator projection does not bind to the terminal investigation");
  }
  for (const header of [
    "x-opsmind-projection-class",
    "x-opsmind-redaction-version",
    "x-opsmind-redaction-count",
  ]) {
    if (!read.response.headers.get(header)) throw new Error(`missing projection assurance header ${header}`);
  }
  if (forbidden.test(read.body)) throw new Error("operator projection contains prohibited material");
  if (
    read.parsed.rounds !== scenarioContract.analysisRequestsPerRun
    || read.parsed.toolCalls !== scenarioContract.toolCallsPerRun
    || !Array.isArray(read.parsed.evidenceIds)
    || read.parsed.evidenceIds.length !== scenarioContract.evidencePerRun
    || !Array.isArray(read.parsed.pendingToolCalls)
    || read.parsed.pendingToolCalls.length !== 0
  ) {
    throw new Error("operator projection counts do not match the scenario contract");
  }
  if (scenario === "B" && read.parsed.analysis !== null) {
    throw new Error("abstained operator projection must not claim a final analysis");
  }
  if (
    scenario !== "B"
    && (
      !read.parsed.analysis
      || read.parsed.analysis.status !== "complete"
      || read.parsed.analysis.requested_tool_calls?.length !== 0
    )
  ) {
    throw new Error("completed operator projection is missing its safe final analysis");
  }
  if (
    scenario === "C"
    && (
      !Array.isArray(read.parsed.analysis.counter_evidence)
      || read.parsed.analysis.counter_evidence.length < 1
      || read.parsed.analysis.confidence > 0.6
    )
  ) {
    throw new Error("conflict scenario must retain counter-evidence and cautious confidence");
  }
  return {
    runId,
    startMs: started.elapsedMs,
    readMs: read.elapsedMs,
    totalMs: started.elapsedMs + read.elapsedMs,
    correlationId: started.response.headers.get("x-correlation-id"),
    organizationId: started.parsed.organizationId,
    projectId: started.parsed.projectId,
    incidentId: started.parsed.incidentId,
    evidenceIds: started.parsed.evidenceIds,
    status: started.parsed.status,
    operatorProjection: read.parsed,
  };
}

function percentile(values, fraction) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * fraction) - 1)];
}

async function main() {
  const samples = [];
  for (let index = 0; index < warmRuns; index += 1) {
    samples.push(await runOne());
  }
  const providerObservation = await status(
    providerStatusUrl,
    "opsmind-fixture-provider-status-v1",
    new Set(["schema", "scenario", "probe_requests", "analysis_requests", "total_requests"]),
  );
  const prometheusObservation = await status(
    prometheusStatusUrl,
    "opsmind-fixture-prometheus-status-v1",
    new Set(["schema", "query_requests", "ready_requests"]),
  );
  if (
    !Number.isInteger(providerObservation.probe_requests)
    || providerObservation.probe_requests < 1
    || !Number.isInteger(providerObservation.analysis_requests)
    || providerObservation.scenario !== scenario
    || providerObservation.analysis_requests
      !== warmRuns * scenarioContract.analysisRequestsPerRun
    || providerObservation.total_requests
      !== providerObservation.probe_requests + providerObservation.analysis_requests
  ) {
    throw new Error("fixture provider request counts do not match the scenario");
  }
  if (
    !Number.isInteger(prometheusObservation.query_requests)
    || prometheusObservation.query_requests
      !== warmRuns * scenarioContract.queryRequestsPerRun
    || !Number.isInteger(prometheusObservation.ready_requests)
    || prometheusObservation.ready_requests < 1
  ) {
    throw new Error("fixture Prometheus request counts do not match the scenario");
  }

  const totalDurations = samples.map((sample) => sample.totalMs);
  const startDurations = samples.map((sample) => sample.startMs);
  const readDurations = samples.map((sample) => sample.readMs);
  const p95ThresholdMs = Number.parseInt(process.env.OPSMIND_P95_THRESHOLD_MS ?? "5000", 10);
  if (!Number.isInteger(p95ThresholdMs) || p95ThresholdMs < 100 || p95ThresholdMs > 30_000) {
    throw new Error("OPSMIND_P95_THRESHOLD_MS must be between 100 and 30000");
  }
  const p95 = percentile(totalDurations, 0.95);
  const report = {
    schema: "opsmind-cross-service-trace-v1",
    generatedAt: new Date().toISOString(),
    environment: process.env.OPSMIND_ENVIRONMENT ?? "unspecified-non-production",
    scenario,
    warmRuns,
    terminalStatus: samples.every(
      (sample) => sample.status === scenarioContract.terminalStatus,
    ) ? "PASS" : "FAIL",
    latencyMs: {
      p50: percentile(totalDurations, 0.5),
      p95,
      max: Math.max(...totalDurations),
      startP95: percentile(startDurations, 0.95),
      projectionReadP95: percentile(readDurations, 0.95),
      threshold: p95ThresholdMs,
      thresholdPass: p95 <= p95ThresholdMs,
    },
    boundaries: [
      "operator-or-runner",
      "platform-api",
      "ai-runtime",
      "platform-api-round-two",
      "tool-gateway",
      "prometheus",
      "evidence-persistence",
      "operator-projection",
    ],
    fixtureObservations: {
      provider: providerObservation,
      prometheus: prometheusObservation,
    },
    runs: samples.map(({
      runId,
      correlationId,
      organizationId,
      projectId,
      incidentId,
      evidenceIds,
      status,
      totalMs,
      operatorProjection,
    }) => ({
      runId,
      correlationId: correlationId ?? null,
      organizationId,
      projectId,
      incidentId,
      evidenceIds,
      status,
      totalMs,
      operatorProjection,
      evaluationProjection: null,
    })),
  };
  if (!report.latencyMs.thresholdPass) {
    throw new Error(`cross-service p95 ${p95.toFixed(1)}ms exceeds ${p95ThresholdMs}ms`);
  }

  const repositoryRoot = path.resolve(import.meta.dirname, "../../..");
  const output = path.resolve(
    process.env.OPSMIND_TRACE_REPORT ?? ".opsmind/reports/cross-service-trace.json",
  );
  const reportRoot = path.resolve(repositoryRoot, ".opsmind/reports");
  const evidence = prepareValidationEvidence({
    repositoryRoot,
    configuredArtifactRoot: reportRoot,
    configuredEvidencePath: output,
    defaultRelativePath: "cross-service-trace.json",
    evidenceEnvironmentName: "OPSMIND_TRACE_REPORT",
  });
  if (evidence.error || evidence.evidencePath !== output) {
    throw new Error(evidence.error ?? "cross-service trace path is unavailable");
  }
  publishValidationEvidence(output, `${JSON.stringify(report, null, 2)}\n`);
  console.log(
    `CrossServiceTrace=PASS Scenario=${scenario} WarmRuns=${warmRuns} `
      + `P50Ms=${report.latencyMs.p50.toFixed(1)} P95Ms=${report.latencyMs.p95.toFixed(1)}`,
  );
}

try {
  await main();
} catch (error) {
  console.error(error instanceof Error ? error.stack : "Cross-service runner failed safely.");
  process.exitCode = 1;
  await new Promise((resolve) => setTimeout(resolve, 250));
}
