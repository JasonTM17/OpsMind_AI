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

test("CI starts the receipt server after the live scrape and before alert delivery", () => {
  const composeStepStart = prQualityWorkflow.indexOf("      - name: Build, start, and probe all application services");
  const composeStepEnd = prQualityWorkflow.indexOf("\n      - name:", composeStepStart + 1);
  assert.ok(composeStepStart >= 0 && composeStepEnd > composeStepStart, "the Compose CI step must exist");
  const composeStep = prQualityWorkflow.slice(composeStepStart, composeStepEnd);
  const count = (literal) => composeStep.split(literal).length - 1;
  const scrapeReady = `test "$live_prometheus_verified" = true`;
  const receiptServerStart = "node scripts/validation/phase-09-alert-receipt-server.mjs";
  const receiptReady = `test "$receipt_ready" = true`;
  const receiptCleanup = `trap 'kill "$receipt_pid" 2>/dev/null || true; wait "$receipt_pid" 2>/dev/null || true' EXIT`;
  const callbackToken = 'randomBytes(32).toString("hex")';
  const receiptId = 'randomUUID())';
  const callbackUrl = "callback_token=%s";
  const callbackOwnership = 'sudo chown 65534:65534 "$receipt_receiver_url_file"';
  const callbackTokenArgument = '--callback-token "$receipt_nonce"';
  const receiptIdArgument = '--receipt-id "$receipt_id"';
  const receiptIdAnnotation = "opsmind_ci_receipt_id";
  const deliveryTimeout = "timeout --preserve-status --kill-after=5s 20s";
  const curlTimeout = "--connect-timeout 5 --max-time 15";
  const alertmanagerPost = "http://alertmanager:9093/api/v2/alerts";
  const scrapeReadyIndex = composeStep.indexOf(scrapeReady);
  const receiptServerIndex = composeStep.indexOf(receiptServerStart);
  const receiptReadyIndex = composeStep.indexOf(receiptReady, receiptServerIndex);
  const receiptCleanupIndex = composeStep.indexOf(receiptCleanup, receiptServerIndex);
  const callbackTokenIndex = composeStep.indexOf(callbackToken);
  const receiptIdIndex = composeStep.indexOf(receiptId);
  const callbackUrlIndex = composeStep.indexOf(callbackUrl);
  const callbackOwnershipIndex = composeStep.indexOf(callbackOwnership);
  const callbackTokenArgumentIndex = composeStep.indexOf(callbackTokenArgument, receiptServerIndex);
  const receiptIdArgumentIndex = composeStep.indexOf(receiptIdArgument, receiptServerIndex);
  const receiptIdAnnotationIndex = composeStep.indexOf(receiptIdAnnotation, receiptServerIndex);
  const deliveryTimeoutIndex = composeStep.indexOf(deliveryTimeout, receiptServerIndex);
  const curlTimeoutIndex = composeStep.indexOf(curlTimeout, receiptServerIndex);
  const alertmanagerPostIndex = composeStep.indexOf(alertmanagerPost);

  assert.equal(count(receiptServerStart), 1);
  assert.equal(count(deliveryTimeout), 1);
  assert.equal(count(alertmanagerPost), 1);
  assert.ok(scrapeReadyIndex >= 0, "the CI workflow must verify the live scrape first");
  assert.ok(callbackTokenIndex >= 0 && callbackUrlIndex > callbackTokenIndex, "the CI callback must use a per-run random token");
  assert.ok(receiptIdIndex >= 0, "the CI alert must use a per-run receipt identifier");
  assert.ok(callbackOwnershipIndex > callbackUrlIndex, "the callback file must be readable only by Alertmanager's runtime identity");
  assert.ok(callbackUrlIndex < receiptServerIndex, "Alertmanager must receive the callback token before Compose starts");
  assert.ok(receiptServerIndex > scrapeReadyIndex, "the receipt server must not expire during Compose startup");
  assert.ok(receiptCleanupIndex > receiptServerIndex, "the receipt server must be reaped on every Compose outcome");
  assert.ok(receiptReadyIndex > receiptCleanupIndex, "the receipt server must become ready before alert delivery");
  assert.ok(callbackTokenArgumentIndex > receiptServerIndex, "the receipt server must validate the callback token");
  assert.ok(receiptIdArgumentIndex > callbackTokenArgumentIndex, "the receipt server must validate the injected receipt identifier");
  assert.ok(deliveryTimeoutIndex > receiptReadyIndex, "the delivery command must be bounded below the receipt timeout");
  assert.ok(curlTimeoutIndex > deliveryTimeoutIndex, "the alert request must have its own connection and overall bounds");
  assert.ok(receiptReadyIndex < alertmanagerPostIndex, "the receipt server must be ready before the Alertmanager POST");
  assert.ok(curlTimeoutIndex < alertmanagerPostIndex, "the request timeout bounds must apply to the Alertmanager POST");
  assert.ok(receiptIdAnnotationIndex < alertmanagerPostIndex, "the Alertmanager POST must carry the injected receipt identifier");
  assert.ok(receiptServerIndex < alertmanagerPostIndex, "the receipt server must be ready before alert delivery");
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
  const callbackToken = "a".repeat(64);
  const receiptId = "11111111-1111-4111-8111-111111111111";
  const child = spawn(process.execPath, [
    receiptServer,
    "--host", "127.0.0.1",
    "--port", String(port),
    "--timeout-ms", "10000",
    "--callback-token", callbackToken,
    "--receipt-id", receiptId,
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

  const rejectedResponse = await fetch(
    `http://127.0.0.1:${port}/phase-09-alert-receipt?callback_token=${"b".repeat(64)}`,
    { method: "POST", headers: { "content-type": "application/json" } },
  );
  assert.equal(rejectedResponse.status, 404);

  const unrelatedAlert = {
    status: "firing",
    labels: {
      alertname: "OpsMindWorkflowReconciliationBlocked",
      component: "workflow-reconciliation",
      severity: "critical",
    },
    annotations: {
      summary: "Unrelated bounded receipt.",
      description: "This alert was not injected by this CI run.",
    },
  };
  const expectedAlert = {
    status: "firing",
    labels: {
      alertname: "OpsMindWorkflowReconciliationBlocked",
      component: "workflow-reconciliation",
      severity: "critical",
    },
    annotations: {
      summary: "Workflow reconciliation is blocked",
      description: "Synthetic bounded conformance receipt.",
      opsmind_ci_receipt_id: receiptId,
    },
  };
  const webhookPayload = (alerts) => ({
    version: "4",
    receiver: "opsmind-phase-09-receiver",
    status: "firing",
    truncatedAlerts: 0,
    alerts,
  });
  const unrelatedResponse = await fetch(`http://127.0.0.1:${port}/phase-09-alert-receipt?callback_token=${callbackToken}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(webhookPayload([unrelatedAlert])),
  });
  assert.equal(unrelatedResponse.status, 202);

  const oversizedResponse = await fetch(`http://127.0.0.1:${port}/phase-09-alert-receipt?callback_token=${callbackToken}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(webhookPayload([expectedAlert, ...Array.from({ length: 8 }, () => unrelatedAlert)])),
  });
  assert.equal(oversizedResponse.status, 202);

  const ambiguousResponse = await fetch(`http://127.0.0.1:${port}/phase-09-alert-receipt?callback_token=${callbackToken}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(webhookPayload([expectedAlert, expectedAlert])),
  });
  assert.equal(ambiguousResponse.status, 202);

  const response = await fetch(`http://127.0.0.1:${port}/phase-09-alert-receipt?callback_token=${callbackToken}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(webhookPayload([unrelatedAlert, expectedAlert])),
  });
  assert.equal(response.status, 204);
  const exitCode = await new Promise((resolve) => child.once("exit", resolve));
  assert.equal(exitCode, 0, stderr);
  assert.match(stdout, /Phase9AlertReceipt=PASS/u);
  assert.match(stdout, /EvidenceClass=CI_LOCAL_ROUTING_CONFORMANCE/u);
  assert.match(stdout, /CallbackAlerts=2/u);
  assert.match(stdout, /MatchedAlerts=1/u);
  assert.match(stdout, /ReceiptSha256=[a-f0-9]{64}/u);
  assert.doesNotMatch(stdout, /Synthetic bounded/u);
  assert.doesNotMatch(stdout, new RegExp(receiptId, "u"));
});
