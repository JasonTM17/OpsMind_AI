import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const liveValidator = path.join(
  scriptDirectory,
  "validate-phase-09-live-prometheus-scrape.mjs",
);
const receiptServer = path.join(
  scriptDirectory,
  "phase-09-alert-receipt-server.mjs",
);
const prQualityWorkflow = fs.readFileSync(
  path.resolve(scriptDirectory, "..", "..", ".github", "workflows", "pr-quality.yml"),
  "utf8",
);

const validTargets = {
  status: "success",
  data: {
    activeTargets: [{
      labels: {
        instance: "platform-api:8082",
        job: "opsmind-workflow-reconciliation",
      },
      scrapeUrl: "http://platform-api:8082/actuator/prometheus",
      health: "up",
      lastError: "",
    }],
  },
};
const gaugeNames = [
  "opsmind_workflow_reconciliation_ready",
  "opsmind_workflow_reconciliation_pending",
  "opsmind_workflow_reconciliation_oldest_pending_age_seconds",
  "opsmind_workflow_reconciliation_blocked",
  "opsmind_workflow_reconciliation_retention_ineligible",
];
const validSeries = {
  status: "success",
  data: {
    resultType: "vector",
    result: gaugeNames.map((name, index) => ({
      metric: {
        __name__: name,
        instance: "platform-api:8082",
        job: "opsmind-workflow-reconciliation",
      },
      value: [1_722_500_000 + index, index === 0 ? "1" : "0"],
    })),
  },
};

const runLiveValidator = (targets, series) => {
  const temporaryRoot = fs.mkdtempSync(path.join(os.tmpdir(), "opsmind-phase9-observability-"));
  try {
    const targetsPath = path.join(temporaryRoot, "targets.json");
    const seriesPath = path.join(temporaryRoot, "series.json");
    fs.writeFileSync(targetsPath, JSON.stringify(targets));
    fs.writeFileSync(seriesPath, JSON.stringify(series));
    return spawnSync(process.execPath, [liveValidator, targetsPath, seriesPath], {
      encoding: "utf8",
    });
  } finally {
    fs.rmSync(temporaryRoot, { recursive: true, force: true });
  }
};

test("live validator accepts one healthy target and the five bounded gauges", () => {
  const result = runLiveValidator(validTargets, validSeries);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /Phase9LiveScrape=PASS/u);
  assert.match(result.stdout, /Series=5/u);
});

test("live validator rejects a tenant-sensitive label", () => {
  const unsafe = structuredClone(validSeries);
  unsafe.data.result[0].metric.organization_id = "00000000-0000-4000-8000-000000000001";
  const result = runLiveValidator(validTargets, unsafe);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /violates identity, label, or value bounds/u);
});

test("CI submits a bare alert array to Alertmanager API v2", () => {
  const alert = `--data '[{"labels":{"alertname":"OpsMindWorkflowReconciliationBlocked"`;
  assert.ok(
    prQualityWorkflow.includes(alert),
    "the Alertmanager API v2 request must be a JSON array, not a webhook envelope",
  );
  assert.ok(
    !prQualityWorkflow.includes(`--data '{"alerts":`),
    "the Alertmanager API v2 request must not use a webhook envelope",
  );
});

const reservePort = async () => await new Promise((resolve, reject) => {
  const server = net.createServer();
  server.once("error", reject);
  server.listen(0, "127.0.0.1", () => {
    const address = server.address();
    server.close(() => resolve(address.port));
  });
});

test("receipt server emits only sanitized CI-local routing evidence", async () => {
  const port = await reservePort();
  const child = spawn(process.execPath, [
    receiptServer,
    "--host", "127.0.0.1",
    "--port", String(port),
    "--timeout-ms", "10000",
  ], { stdio: ["ignore", "pipe", "pipe"] });
  let stdout = "";
  let stderr = "";
  child.stdout.setEncoding("utf8");
  child.stderr.setEncoding("utf8");
  child.stdout.on("data", (chunk) => { stdout += chunk; });
  child.stderr.on("data", (chunk) => { stderr += chunk; });

  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("receipt server did not become ready")), 5_000);
    const ready = (chunk) => {
      if (String(chunk).includes("Phase9AlertReceiptServer=READY")) {
        clearTimeout(timeout);
        child.stdout.off("data", ready);
        resolve();
      }
    };
    child.stdout.on("data", ready);
    child.once("error", reject);
  });

  const response = await fetch(`http://127.0.0.1:${port}/phase-09-alert-receipt`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      version: "4",
      receiver: "opsmind-phase-09-receiver",
      status: "firing",
      truncatedAlerts: 0,
      alerts: [{
        status: "firing",
        labels: {
          alertname: "OpsMindWorkflowReconciliationBlocked",
          component: "workflow-reconciliation",
          severity: "critical",
        },
        annotations: {
          summary: "Workflow reconciliation is blocked",
          description: "Synthetic bounded conformance receipt.",
        },
      }],
    }),
  });
  assert.equal(response.status, 204);
  const exitCode = await new Promise((resolve) => child.once("exit", resolve));
  assert.equal(exitCode, 0, stderr);
  assert.match(stdout, /Phase9AlertReceipt=PASS/u);
  assert.match(stdout, /EvidenceClass=CI_LOCAL_ROUTING_CONFORMANCE/u);
  assert.match(stdout, /ReceiptSha256=[a-f0-9]{64}/u);
  assert.doesNotMatch(stdout, /Synthetic bounded/u);
});
