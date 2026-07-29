import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "..", "..");
const prometheusPath = path.join(
  repositoryRoot,
  "deploy",
  "prometheus",
  "prometheus.yml",
);
const recordingRulesPath = path.join(
  repositoryRoot,
  "deploy",
  "prometheus",
  "opsmind-recording-rules.yml",
);
const alertRulesPath = path.join(
  repositoryRoot,
  "deploy",
  "prometheus",
  "opsmind-reconciliation-alerts.yml",
);

const failures = [];
const check = (condition, message) => {
  if (!condition) {
    failures.push(message);
  }
};
const read = (filePath) => fs.readFileSync(filePath, "utf8");
const prometheus = read(prometheusPath);
const recordingRules = read(recordingRulesPath);
const alertRules = read(alertRulesPath);

for (const marker of [
  "- /etc/prometheus/opsmind-recording-rules.yml",
  "- /etc/prometheus/opsmind-reconciliation-alerts.yml",
  "- job_name: opsmind-workflow-reconciliation",
  "honor_labels: false",
  "body_size_limit: 1MB",
  "sample_limit: 256",
  "label_limit: 8",
  "label_name_length_limit: 128",
  "label_value_length_limit: 256",
  "metrics_path: /actuator/prometheus",
  "scheme: http",
  "follow_redirects: false",
  "enable_http2: false",
  "- platform-api:8082",
]) {
  check(prometheus.includes(marker), `Prometheus config is missing: ${marker}`);
}
check(
  (prometheus.match(/^\s+- job_name:/gm) ?? []).length === 1,
  "Prometheus must define only the network-scoped reconciliation scrape",
);
check(
  (prometheus.match(/^\s+- platform-api:8082\s*$/gm) ?? []).length === 1,
  "Prometheus must define exactly one internal scrape target",
);
check(
  /source_labels:\s*\r?\n\s*- __name__\s*\r?\n\s*- outcome\s*\r?\n\s*- operation\s*\r?\n\s*- result/.test(
    prometheus,
  ),
  "metric allowlist must bind name and every variable label",
);

const allowlist = prometheus;
const requiredMetricNames = [
  "opsmind_workflow_reconciliation_ready",
  "opsmind_workflow_reconciliation_pending",
  "opsmind_workflow_reconciliation_oldest_pending_age_seconds",
  "opsmind_workflow_reconciliation_blocked",
  "opsmind_workflow_reconciliation_retention_ineligible",
  "opsmind_workflow_reconciliation_outcomes_total",
  "opsmind_workflow_reconciliation_observation_duration_seconds",
  "opsmind_workflow_reconciliation_convergence_duration_seconds",
];
for (const metricName of requiredMetricNames) {
  check(allowlist.includes(metricName), `scrape allowlist is missing ${metricName}`);
}
check(
  allowlist.includes("(describe|first_history)"),
  "observation operations must be fixed to describe and first_history",
);
check(
  allowlist.includes("(started|rejected|blocked)"),
  "convergence results must be fixed to started, rejected, and blocked",
);
for (const outcome of [
  "match",
  "absence_candidate",
  "verified_absence",
  "released",
  "mismatch",
  "retry",
  "blocked",
  "lease_lost",
  "exhausted",
]) {
  check(allowlist.includes(outcome), `outcome allowlist is missing ${outcome}`);
}
check(
  prometheus.includes(
    "regex: __name__|job|instance|outcome|operation|result|le|quantile",
  ),
  "stored label allowlist is not exact",
);

for (const recordName of [
  "opsmind:workflow_reconciliation:ready",
  "opsmind:workflow_reconciliation:pending",
  "opsmind:workflow_reconciliation:oldest_pending_age_seconds",
  "opsmind:workflow_reconciliation:blocked",
  "opsmind:workflow_reconciliation:retention_ineligible",
  "opsmind:workflow_reconciliation:outcomes_total",
  "opsmind:workflow_reconciliation:observation_duration_seconds:mean5m",
  "opsmind:workflow_reconciliation:convergence_duration_seconds:mean5m",
]) {
  check(
    recordingRules.includes(`record: ${recordName}`),
    `recording rule is missing ${recordName}`,
  );
}
check(
  recordingRules.includes(
    "record: opsmind:workflow_reconciliation:ready\n"
      + "        expr: min(opsmind_workflow_reconciliation_ready)",
  ) || recordingRules.includes(
    "record: opsmind:workflow_reconciliation:ready\r\n"
      + "        expr: min(opsmind_workflow_reconciliation_ready)",
  ),
  "readiness must fail closed when any scraped reconciler replica is unready",
);
check(
  /name: opsmind-workflow-reconciliation-recording\s*\r?\n\s+interval: 15s\s*\r?\n\s+limit: 32/.test(
    recordingRules,
  ),
  "reconciliation recording group must cap emitted series at 32",
);

const expectedAlerts = new Map([
  ["OpsMindWorkflowReconciliationBlocked", ["critical", undefined, ":blocked > 0"]],
  ["OpsMindWorkflowReconciliationExhausted", ["critical", undefined, 'outcome="exhausted"']],
  ["OpsMindWorkflowReconciliationRetentionIneligible", ["critical", undefined, ":retention_ineligible > 0"]],
  ["OpsMindWorkflowReconciliationLagWarning", ["warning", "2m", "age_seconds > 30"]],
  ["OpsMindWorkflowReconciliationLagCritical", ["critical", "5m", "age_seconds > 300"]],
  ["OpsMindWorkflowReconcilerNotReady", ["critical", "2m", 'up{job="opsmind-workflow-reconciliation"} == 0']],
  ["OpsMindWorkflowReconciliationNoProgress", ["critical", undefined, "or vector(0)"]],
]);
for (const [name, [severity, duration, expressionMarker]] of expectedAlerts) {
  const alertMatch = alertRules.match(
    new RegExp(
      `- alert: ${name}([\\s\\S]*?)(?=\\n\\s+- alert:|$)`,
    ),
  );
  const alert = alertMatch?.[1] ?? "";
  check(Boolean(alertMatch), `alert is missing ${name}`);
  check(
    alert.includes(`severity: ${severity}`),
    `${name} severity must be ${severity}`,
  );
  check(
    duration === undefined
      ? !/^\s+for:/m.test(alert)
      : alert.includes(`for: ${duration}`),
    `${name} duration must be ${duration ?? "immediate"}`,
  );
  check(
    alert.includes(expressionMarker),
    `${name} expression is missing ${expressionMarker}`,
  );
  check(
    alert.includes(
      "runbook_url: https://github.com/JasonTM17/OpsMind_AI/"
        + "blob/main/docs/runbooks/investigation-workflow-cutover.md",
    ),
    `${name} must link the owned workflow cutover runbook`,
  );
}
check(
  (alertRules.match(/^\s+- alert:/gm) ?? []).length === expectedAlerts.size,
  "alert file must contain exactly the seven contract alerts",
);
const exhaustedAlert = alertRules.match(
  /- alert: OpsMindWorkflowReconciliationExhausted([\s\S]*?)(?=\n\s+- alert:|$)/,
)?.[1] ?? "";
check(
  exhaustedAlert.includes(
    'sum(increase(opsmind_workflow_reconciliation_outcomes_total{outcome="exhausted"}[5m])) > 0',
  ),
  "exhaustion must increase raw per-replica counters before aggregation",
);
check(
  !exhaustedAlert.includes("opsmind:workflow_reconciliation:outcomes"),
  "exhaustion must not increase a pre-aggregated counter",
);
const noProgressAlert = alertRules.match(
  /- alert: OpsMindWorkflowReconciliationNoProgress([\s\S]*?)(?=\n\s+- alert:|$)/,
)?.[1] ?? "";
check(
  noProgressAlert.includes(
    "opsmind:workflow_reconciliation:oldest_pending_age_seconds > 300",
  ),
  "no-progress must require a pending item older than five minutes",
);
check(
  noProgressAlert.includes(
    "sum(increase(opsmind_workflow_reconciliation_outcomes_total[5m]))",
  ),
  "no-progress must increase raw per-replica counters before aggregation",
);
check(
  !noProgressAlert.includes("opsmind:workflow_reconciliation:outcomes"),
  "no-progress must not increase a pre-aggregated counter",
);
check(
  /name: opsmind-workflow-reconciliation-alerts\s*\r?\n\s+interval: 15s\s*\r?\n\s+limit: 8/.test(
    alertRules,
  ),
  "reconciliation alert group must cap active alert series at eight",
);

const serializedRules = `${recordingRules}\n${alertRules}`;
check(
  !/\$labels\b/.test(serializedRules),
  "alerts must not render scraped labels into operator text",
);
check(
  !/\b(tenant|organization|run|event|workflow_id|error)(_id|_code)?\b\s*[=:]/i.test(
    serializedRules,
  ),
  "recording or alert rules reference a forbidden high-cardinality label",
);

if (failures.length > 0) {
  console.error("Phase 9 reconciliation observability validation failed:");
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exit(1);
}

console.log(
  "Phase 9 reconciliation observability validation passed: " +
    "internal scrape, bounded labels, aggregate recordings, and seven alerts.",
);
