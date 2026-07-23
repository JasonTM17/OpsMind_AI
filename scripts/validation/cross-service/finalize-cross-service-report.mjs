import { execFileSync } from "node:child_process";
import { readFileSync, renameSync, writeFileSync } from "node:fs";
import path from "node:path";

const reportPath = path.resolve(
  process.env.OPSMIND_TRACE_REPORT ?? ".opsmind/reports/cross-service-trace.json",
);
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

const report = JSON.parse(readFileSync(reportPath, "utf8"));
if (
  report.schema !== "opsmind-cross-service-trace-v1"
  || !Number.isInteger(report.warmRuns)
  || report.warmRuns < 1
) {
  throw new Error("cross-service report is invalid");
}
const counts = {
  investigationRuns: Number(process.env.OPSMIND_COUNT_INVESTIGATION_RUNS),
  evidenceRecords: Number(process.env.OPSMIND_COUNT_EVIDENCE_RECORDS),
  analysisInvocations: Number(process.env.OPSMIND_COUNT_ANALYSIS_INVOCATIONS),
  toolReceipts: Number(process.env.OPSMIND_COUNT_TOOL_RECEIPTS),
  toolAuditEvents: Number(process.env.OPSMIND_COUNT_TOOL_AUDIT_EVENTS),
};
if (
  counts.investigationRuns !== report.warmRuns
  || counts.evidenceRecords !== report.warmRuns
  || counts.analysisInvocations !== report.warmRuns * 2
  || counts.toolReceipts !== report.warmRuns
  || counts.toolAuditEvents < report.warmRuns
) {
  throw new Error("durable cross-service counts do not match the completed runs");
}

const repositoryRoot = path.resolve(import.meta.dirname, "../../..");
const gitHead = execFileSync("git", ["rev-parse", "HEAD"], {
  cwd: repositoryRoot,
  encoding: "utf8",
}).trim();
const gitStatus = execFileSync("git", ["status", "--porcelain=v1", "--untracked-files=all"], {
  cwd: repositoryRoot,
  encoding: "utf8",
});
report.durableState = counts;
report.source = {
  gitHead,
  workingTreeClean: gitStatus.trim().length === 0,
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

const temporaryPath = `${reportPath}.finalizing`;
writeFileSync(temporaryPath, `${JSON.stringify(report, null, 2)}\n`, {
  encoding: "utf8",
  flag: "wx",
});
renameSync(temporaryPath, reportPath);
process.stdout.write(
  `CrossServiceDurableState=PASS Runs=${counts.investigationRuns} Evidence=${counts.evidenceRecords} `
    + `Analysis=${counts.analysisInvocations} ToolReceipts=${counts.toolReceipts}\n`,
);
