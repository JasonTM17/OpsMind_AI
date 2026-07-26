import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import path from "node:path";

import {
  prepareValidationEvidence,
  publishValidationEvidence,
} from "../safe-validation-evidence.mjs";
import {
  evaluationProjectionIntegrityChecks,
} from "../../../evaluation/runner/cross-service-evaluation-projection-verifier.mjs";

const DIGEST = /^sha256:[0-9a-f]{64}$/u;
const PROJECTION_SCHEMA = "opsmind-cross-service-evaluation-projection-v1";
const repositoryRoot = path.resolve(import.meta.dirname, "../../..");
const reportPath = path.resolve(
  process.env.OPSMIND_TRACE_REPORT ?? ".opsmind/reports/cross-service-trace.json",
);
const scenario = process.env.OPSMIND_CROSS_SERVICE_SCENARIO;
const scenarioContract = {
  A: { status: "COMPLETED", analyses: 2, evidence: 1, receipts: 1 },
  B: { status: "ABSTAINED", analyses: 1, evidence: 0, receipts: 0 },
  C: { status: "COMPLETED", analyses: 2, evidence: 2, receipts: 2 },
}[scenario];
if (!scenarioContract) throw new Error("OPSMIND_CROSS_SERVICE_SCENARIO is invalid");

const requiredCounts = [
  "OPSMIND_COUNT_INVESTIGATION_RUNS",
  "OPSMIND_COUNT_EVIDENCE_RECORDS",
  "OPSMIND_COUNT_ANALYSIS_INVOCATIONS",
  "OPSMIND_COUNT_TOOL_RECEIPTS",
  "OPSMIND_COUNT_TOOL_AUDIT_EVENTS",
];
for (const name of requiredCounts) {
  if (!/^\d+$/u.test(process.env[name] ?? "")) throw new Error(`${name} is invalid`);
}

function digestReferenceValid(reference, type, domain) {
  return reference
    && reference.digest_type === type
    && reference.digest_domain === domain
    && DIGEST.test(reference.digest ?? "");
}

function rawByteReference(filePath, domain) {
  return {
    digest_type: "raw-bytes",
    digest_domain: domain,
    digest: `sha256:${createHash("sha256").update(readFileSync(filePath)).digest("hex")}`,
  };
}

function requiredPath(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return path.resolve(value);
}

const sourceDomain = "opsmind.cross-service-source.raw-bytes/v1";
const exportQueryReference = rawByteReference(
  requiredPath("OPSMIND_EXPORT_QUERY_SOURCE"),
  sourceDomain,
);
const connectorManifestReference = rawByteReference(
  requiredPath("OPSMIND_CONNECTOR_MANIFEST_SOURCE"),
  sourceDomain,
);
const report = JSON.parse(readFileSync(reportPath, "utf8"));
if (
  report.schema !== "opsmind-cross-service-trace-v1"
  || report.scenario !== scenario
  || !Number.isInteger(report.warmRuns)
  || report.warmRuns < 1
  || !Array.isArray(report.runs)
  || report.runs.length !== report.warmRuns
) {
  throw new Error("cross-service report is invalid");
}

let projectedEvents = 0;
let projectedEvidence = 0;
let projectedAnalyses = 0;
let projectedReceipts = 0;
for (const run of report.runs) {
  const projection = run.evaluationProjection;
  if (
    !projection
    || projection.schemaVersion !== PROJECTION_SCHEMA
    || projection.scope?.runId !== run.runId
    || projection.scope?.organizationId !== run.organizationId
    || projection.scope?.projectId !== run.projectId
    || projection.scope?.incidentId !== run.incidentId
    || projection.run?.runId !== run.runId
    || projection.run?.status !== scenarioContract.status
    || projection.run?.eventCount !== projection.timeline?.length
    || projection.acceptedAnalyses?.length !== projection.run?.rounds
    || projection.acceptedAnalyses.length !== scenarioContract.analyses
    || projection.evidenceRecords?.length !== run.evidenceIds?.length
    || projection.evidenceRecords.length !== scenarioContract.evidence
    || projection.toolReceipts?.length !== projection.run?.toolCalls
    || projection.toolReceipts.length !== scenarioContract.receipts
    || projection.sourceExport?.classification
      !== "TRANSIENT_SYNTHETIC_CROSS_SERVICE_EXPORT"
  ) {
    throw new Error("evaluation projection counts or scope do not match the completed run");
  }
  if (evaluationProjectionIntegrityChecks(run).some((check) => check !== true)) {
    throw new Error("evaluation projection integrity verification failed");
  }
  if (
    !digestReferenceValid(
      projection.sourceExport.byteDigest,
      "raw-bytes",
      "opsmind.cross-service-evaluation-export.raw-bytes/v1",
    )
    || !digestReferenceValid(
      projection.sourceExport.canonicalDigest,
      "canonical-json",
      "opsmind.cross-service-evaluation-export/v1",
    )
    || !digestReferenceValid(
      projection.sourceExport.queryManifestByteDigest,
      "raw-bytes",
      "opsmind.cross-service-evaluation-query-manifest.raw-bytes/v1",
    )
    || projection.sourceExport.queryManifestReference
      !== "repository://scripts/validation/cross-service/cross-service-evaluation-export.sql"
    || projection.sourceExport.queryManifestByteDigest.digest
      !== exportQueryReference.digest
    || !digestReferenceValid(
      projection.canonicalDigest,
      "canonical-json",
      "opsmind.cross-service-evaluation-projection/v1",
    )
  ) {
    throw new Error("evaluation projection provenance digest is invalid");
  }
  const fragmentGroups = [
    [projection.timeline, "opsmind.investigation-event-fragment/v1"],
    [projection.acceptedAnalyses, "opsmind.accepted-analysis-fragment/v1"],
    [projection.evidenceRecords, "opsmind.evidence-metadata-fragment/v1"],
    [projection.toolReceipts, "opsmind.tool-receipt-fragment/v1"],
  ];
  for (const [fragments, domain] of fragmentGroups) {
    if (fragments.some((fragment) => !digestReferenceValid(
      fragment.fragmentDigest,
      "canonical-json",
      domain,
    ))) {
      throw new Error("evaluation projection fragment digest is invalid");
    }
  }
  if (projection.toolReceipts.some((receipt) => (
    receipt.connectorId !== "prometheus-read-only"
    || receipt.connectorProfile !== "prometheus"
    || receipt.connector !== "observability"
    || receipt.operation !== "metrics.query"
    || receipt.riskClass !== "read-only"
    || receipt.connectorManifestByteDigest !== connectorManifestReference.digest
  ))) {
    throw new Error("Tool Gateway connector provenance does not match the executed manifest");
  }
  const finalAnalysis = projection.acceptedAnalyses.at(-1)?.response;
  if (
    (scenario === "B" && (
      finalAnalysis?.status !== "abstain"
      || finalAnalysis.hypotheses?.length !== 0
      || finalAnalysis.citations?.length !== 0
      || finalAnalysis.requested_tool_calls?.length !== 0
      || finalAnalysis.missing_evidence?.length < 1
    ))
    || (scenario === "C" && (
      finalAnalysis?.status !== "complete"
      || finalAnalysis.counter_evidence?.length < 1
      || finalAnalysis.confidence > 0.6
    ))
  ) {
    throw new Error("terminal analysis does not satisfy its scenario safety contract");
  }
  projectedEvents += projection.timeline.length;
  projectedEvidence += projection.evidenceRecords.length;
  projectedAnalyses += projection.acceptedAnalyses.length;
  projectedReceipts += projection.toolReceipts.length;
}

const counts = {
  investigationRuns: Number(process.env.OPSMIND_COUNT_INVESTIGATION_RUNS),
  investigationEvents: projectedEvents,
  evidenceRecords: Number(process.env.OPSMIND_COUNT_EVIDENCE_RECORDS),
  analysisInvocations: Number(process.env.OPSMIND_COUNT_ANALYSIS_INVOCATIONS),
  toolReceipts: Number(process.env.OPSMIND_COUNT_TOOL_RECEIPTS),
  toolAuditEvents: Number(process.env.OPSMIND_COUNT_TOOL_AUDIT_EVENTS),
};
if (
  counts.investigationRuns !== report.warmRuns
  || counts.evidenceRecords !== projectedEvidence
  || counts.analysisInvocations !== projectedAnalyses
  || counts.toolReceipts !== projectedReceipts
  || counts.toolAuditEvents !== projectedReceipts
) {
  throw new Error("durable cross-service counts do not match harvested projections");
}

const gitHead = execFileSync("git", ["rev-parse", "HEAD"], {
  cwd: repositoryRoot,
  encoding: "utf8",
}).trim();
const gitStatus = execFileSync(
  "git",
  ["status", "--porcelain=v1", "--untracked-files=all"],
  { cwd: repositoryRoot, encoding: "utf8" },
);
const executableDomain = "opsmind.cross-service-executable.raw-bytes/v1";
report.durableState = counts;
report.source = {
  gitHead,
  workingTreeClean: gitStatus.trim().length === 0,
  postgresImage: process.env.OPSMIND_POSTGRES_IMAGE,
  executables: {
    java: rawByteReference(requiredPath("OPSMIND_JAVA_EXECUTABLE"), executableDomain),
    node: rawByteReference(requiredPath("OPSMIND_NODE_EXECUTABLE"), executableDomain),
    python: rawByteReference(requiredPath("OPSMIND_PYTHON_EXECUTABLE"), executableDomain),
  },
  binaries: {
    platformJar: rawByteReference(requiredPath("OPSMIND_PLATFORM_JAR"), executableDomain),
    toolGatewayJar: rawByteReference(requiredPath("OPSMIND_GATEWAY_JAR"), executableDomain),
  },
  harnessSources: {
    fixtureProvider: rawByteReference(
      requiredPath("OPSMIND_FIXTURE_PROVIDER_SOURCE"),
      sourceDomain,
    ),
    investigationRunner: rawByteReference(
      requiredPath("OPSMIND_INVESTIGATION_RUNNER_SOURCE"),
      sourceDomain,
    ),
    exportQuery: exportQueryReference,
    projector: rawByteReference(requiredPath("OPSMIND_PROJECTOR_SOURCE"), sourceDomain),
    connectorManifest: connectorManifestReference,
  },
};
report.adapters = [
  "spring-oidc-resource-server",
  "platform-http-ai-runtime-client",
  "platform-oauth-workload-token-provider",
  "platform-rs256-capability-issuers",
  "tool-gateway-rs256-verifiers",
  "tool-gateway-prometheus-http-connector",
  "postgres-forced-rls-stores",
];

const reportRoot = path.resolve(repositoryRoot, ".opsmind/reports");
const evidence = prepareValidationEvidence({
  repositoryRoot,
  configuredArtifactRoot: reportRoot,
  configuredEvidencePath: reportPath,
  defaultRelativePath: "cross-service-trace.json",
  evidenceEnvironmentName: "OPSMIND_TRACE_REPORT",
});
if (evidence.error || evidence.evidencePath !== reportPath) {
  throw new Error(evidence.error ?? "final report path is unavailable");
}
publishValidationEvidence(reportPath, `${JSON.stringify(report, null, 2)}\n`);
process.stdout.write(
  `CrossServiceDurableState=PASS Scenario=${scenario} Runs=${counts.investigationRuns} `
    + `Evidence=${counts.evidenceRecords} Analysis=${counts.analysisInvocations} `
    + `ToolReceipts=${counts.toolReceipts}\n`,
);
