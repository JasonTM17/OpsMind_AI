import crypto from "node:crypto";
import http from "node:http";

const options = {
  host: "0.0.0.0",
  port: 19_093,
  timeoutMs: 60_000,
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
  } else {
    throw new Error(`Unsupported argument: ${name}.`);
  }
}
if (!Number.isInteger(options.port) || options.port < 1024 || options.port > 65_535
    || !Number.isInteger(options.timeoutMs)
    || options.timeoutMs < 1_000 || options.timeoutMs > 300_000) {
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
  if (request.method !== "POST" || request.url !== "/phase-09-alert-receipt"
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
      const alerts = payload?.alerts;
      const alert = Array.isArray(alerts) && alerts.length === 1 ? alerts[0] : undefined;
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
          || !alert
          || alert?.status !== "firing"
          || labelNames.join(",") !== "alertname,component,severity"
          || labels.alertname !== "OpsMindWorkflowReconciliationBlocked"
          || labels.component !== "workflow-reconciliation"
          || labels.severity !== "critical"
          || annotationNames.some((name) => !["description", "runbook_url", "summary"].includes(name))
          || /(token|secret|password|api[_-]?key|routing[_-]?key|bearer|tenant|organization_id|run_id|workflow_id)/i.test(
            body.toString("utf8"),
          )) {
        throw new Error("Alertmanager receipt violates the bounded conformance contract.");
      }

      accepted = true;
      const digest = crypto.createHash("sha256").update(body).digest("hex");
      response.writeHead(204);
      response.end();
      process.stdout.write(
        "Phase9AlertReceipt=PASS\n"
          + "EvidenceClass=CI_LOCAL_ROUTING_CONFORMANCE\n"
          + "Receiver=opsmind-phase-09-receiver\n"
          + "Alerts=1\n"
          + `BodyBytes=${body.length}\n`
          + `ReceiptSha256=${digest}\n`,
      );
      server.close();
    } catch (error) {
      fail(response, 400, "invalid-alertmanager-receipt");
      process.stderr.write(`Phase9AlertReceipt=BLOCK Reason=${error.message}\n`);
      process.exitCode = 1;
      server.close();
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
