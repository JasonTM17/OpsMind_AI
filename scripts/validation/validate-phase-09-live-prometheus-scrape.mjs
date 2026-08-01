import fs from "node:fs";

const [targetsPath, seriesPath] = process.argv.slice(2);
if (!targetsPath || !seriesPath) {
  process.stderr.write(
    "Usage: node validate-phase-09-live-prometheus-scrape.mjs <targets.json> <series.json>\n",
  );
  process.exit(2);
}

const readJson = (filePath, maximumBytes, label) => {
  const body = fs.readFileSync(filePath);
  if (body.length === 0 || body.length > maximumBytes) {
    throw new Error(`${label} violates the byte ceiling.`);
  }
  return { body, value: JSON.parse(body.toString("utf8")) };
};

const targets = readJson(targetsPath, 131_072, "Prometheus target response");
const activeTargets = targets.value?.data?.activeTargets;
if (targets.value?.status !== "success"
    || !Array.isArray(activeTargets)
    || activeTargets.length !== 1) {
  throw new Error("Prometheus target response must contain one active target.");
}

const [target] = activeTargets;
const targetLabelNames = target?.labels && typeof target.labels === "object"
  ? Object.keys(target.labels).sort()
  : [];
if (target?.health !== "up"
    || target?.scrapeUrl !== "http://platform-api:8082/actuator/prometheus"
    || target?.lastError !== ""
    || targetLabelNames.join(",") !== "instance,job"
    || target.labels.instance !== "platform-api:8082"
    || target.labels.job !== "opsmind-workflow-reconciliation") {
  throw new Error("The exact internal reconciliation scrape target is not healthy.");
}

const series = readJson(seriesPath, 65_536, "Prometheus series response");
const result = series.value?.data?.result;
if (series.value?.status !== "success"
    || series.value?.data?.resultType !== "vector"
    || !Array.isArray(result)
    || result.length !== 5) {
  throw new Error("Prometheus reconciliation query must return exactly five gauges.");
}

const expectedMetrics = new Set([
  "opsmind_workflow_reconciliation_ready",
  "opsmind_workflow_reconciliation_pending",
  "opsmind_workflow_reconciliation_oldest_pending_age_seconds",
  "opsmind_workflow_reconciliation_blocked",
  "opsmind_workflow_reconciliation_retention_ineligible",
]);
const forbiddenLabel = /(tenant|organization|project|incident|run|workflow|event|error|user|actor|trace|payload|prompt|token|secret)/i;
for (const item of result) {
  const labels = item?.metric;
  const labelNames = labels && typeof labels === "object"
    ? Object.keys(labels).sort()
    : [];
  if (labelNames.join(",") !== "__name__,instance,job"
      || !expectedMetrics.delete(labels.__name__)
      || labels.instance !== "platform-api:8082"
      || labels.job !== "opsmind-workflow-reconciliation"
      || labelNames.some((name) => forbiddenLabel.test(name))
      || !Array.isArray(item.value)
      || item.value.length !== 2
      || !Number.isFinite(item.value[0])
      || typeof item.value[1] !== "string"
      || !/^-?(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?$/u.test(item.value[1])) {
    throw new Error("A reconciliation series violates identity, label, or value bounds.");
  }
}
if (expectedMetrics.size !== 0) {
  throw new Error("One or more required reconciliation gauges are absent.");
}

process.stdout.write(
  "Phase9LiveScrape=PASS\n"
    + "Target=platform-api:8082\n"
    + "Job=opsmind-workflow-reconciliation\n"
    + `Series=${result.length}\n`
    + "Labels=__name__,instance,job\n"
    + `TargetResponseBytes=${targets.body.length}\n`
    + `SeriesResponseBytes=${series.body.length}\n`,
);
