import crypto from "node:crypto";
import http from "node:http";

const options = {
  host: "0.0.0.0",
  port: 19_093,
  timeoutMs: 60_000,
  callbackToken: "",
  receiptId: "",
};
for (let index = 2; index < process.argv.length; index += 2) {
  const name = process.argv[index];
  const value = process.argv[index + 1];
  if (value === undefined) {
    throw new Error(`Missing value for ${name}.`);
  }
  if (name === "--host") {
    options.host = value;
  } else if (name === "--port") {
    options.port = Number(value);
  } else if (name === "--timeout-ms") {
    options.timeoutMs = Number(value);
  } else if (name === "--callback-token") {
    options.callbackToken = value;
  } else if (name === "--receipt-id") {
    options.receiptId = value;
  } else {
    throw new Error(`Unsupported argument: ${name}.`);
  }
}
if (!Number.isInteger(options.port) || options.port < 1024 || options.port > 65_535
    || !Number.isInteger(options.timeoutMs)
    || options.timeoutMs < 1_000 || options.timeoutMs > 300_000
    || !/^[a-f0-9]{64}$/u.test(options.callbackToken)
    || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u.test(options.receiptId)) {
  throw new Error("Receipt server bounds are invalid.");
}

let accepted = false;
const fail = (response, statusCode, message) => {
  response.writeHead(statusCode, { "content-type": "text/plain" });
  response.end(`${message}\n`);
};
const server = http.createServer((request, response) => {
  if (accepted) {
    fail(response, 409, "receipt-already-accepted");
    return;
  }
  if (request.method !== "POST" || request.url !== `/phase-09-alert-receipt?callback_token=${options.callbackToken}`
      || !String(request.headers["content-type"] ?? "").toLowerCase().startsWith("application/json")) {
    fail(response, 404, "unsupported-receipt-request");
    return;
  }

  const chunks = [];
  let received = 0;
  request.on("data", (chunk) => {
    received += chunk.length;
    if (received > 32_768) {
      request.destroy(new Error("Alertmanager receipt exceeds the body ceiling."));
      return;
    }
    chunks.push(chunk);
  });
  request.on("error", (error) => {
    process.stderr.write(`Phase9AlertReceipt=BLOCK Reason=${error.message}\n`);
    process.exitCode = 1;
    server.close();
  });
  request.on("end", () => {
    try {
      const body = Buffer.concat(chunks);
      const payload = JSON.parse(body.toString("utf8"));
      const alerts = Array.isArray(payload?.alerts) ? payload.alerts : [];
      const matchingAlerts = alerts.filter(
        (candidate) => candidate?.annotations?.opsmind_ci_receipt_id === options.receiptId,
      );
      const alert = matchingAlerts[0];
      const labels = alert?.labels;
      const labelNames = labels && typeof labels === "object"
        ? Object.keys(labels).sort()
        : [];
      const annotationNames = alert?.annotations && typeof alert.annotations === "object"
        ? Object.keys(alert.annotations).sort()
        : [];
      if (payload?.version !== "4"
          || payload?.receiver !== "opsmind-phase-09-receiver"
          || payload?.status !== "firing"
          || payload?.truncatedAlerts !== 0
          || alerts.length === 0 || alerts.length > 8
          || matchingAlerts.length !== 1
          || !alert
          || alert?.status !== "firing"
          || labelNames.join(",") !== "alertname,component,severity"
          || labels.alertname !== "OpsMindWorkflowReconciliationBlocked"
          || labels.component !== "workflow-reconciliation"
          || labels.severity !== "critical"
          || annotationNames.some((name) => !["description", "opsmind_ci_receipt_id", "runbook_url", "summary"].includes(name))
          || alert?.annotations?.opsmind_ci_receipt_id !== options.receiptId
          || /(token|secret|password|api[_-]?key|routing[_-]?key|bearer|tenant|organization_id|run_id|workflow_id)/i.test(
            body.toString("utf8"),
          )) {
        fail(response, 202, "receipt-not-for-this-run");
        return;
      }

      accepted = true;
      const digest = crypto.createHash("sha256").update(body).digest("hex");
      response.writeHead(204);
      response.end();
      process.stdout.write(
        "Phase9AlertReceipt=PASS\n"
          + "EvidenceClass=CI_LOCAL_ROUTING_CONFORMANCE\n"
          + "Receiver=opsmind-phase-09-receiver\n"
          + `CallbackAlerts=${alerts.length}\n`
          + `MatchedAlerts=${matchingAlerts.length}\n`
          + `BodyBytes=${body.length}\n`
          + `ReceiptSha256=${digest}\n`,
      );
      server.close();
    } catch {
      fail(response, 202, "receipt-not-for-this-run");
    }
  });
});

const timeout = setTimeout(() => {
  process.stderr.write("Phase9AlertReceipt=BLOCK Reason=timeout\n");
  process.exitCode = 1;
  server.close();
}, options.timeoutMs);
timeout.unref();
server.on("close", () => clearTimeout(timeout));
server.listen(options.port, options.host, () => {
  process.stdout.write(
    `Phase9AlertReceiptServer=READY Host=${options.host} Port=${options.port}\n`,
  );
});
