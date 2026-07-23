import { timingSafeEqual } from "node:crypto";
import { createServer } from "node:http";

import {
  abstained,
  abstainedRunId,
  analyzingRunId,
  budgetExceeded,
  budgetRunId,
  chunkedRunId,
  completed,
  completedRunId,
  createdRunId,
  crossScopeRunId,
  failed,
  failedRunId,
  forbiddenRunId,
  incident,
  incidentPath,
  invalidJsonRunId,
  invalidMediaRunId,
  invalidRunId,
  invalidUtf8RunId,
  missingRunId,
  noProgressRunId,
  oversizedRunId,
  slowRunId,
  timeoutRunId,
  unauthorizedRunId,
  unclassifiedRunId,
  unavailableRunId,
  uncitedRunId,
  unknownOperationRunId,
  waitingRunId,
} from "./investigation-fixtures.mjs";
import {
  analyzing,
  created,
  noProgress,
  slowCreated,
  waiting,
} from "./investigation-state-fixtures.mjs";

const port = Number(process.env.OPSMIND_E2E_PLATFORM_PORT ?? "4100");
const bearer = process.env.OPSMIND_OPERATOR_TEST_BEARER;
if (!bearer || bearer.length < 32) {
  throw new Error("Runtime-generated E2E bearer is required.");
}

const investigationPath = `${incidentPath}/investigations/`;
const operatorProjectionMediaType =
  "application/vnd.opsmind.operator-projection.v1+json";
const standardRuns = new Map([
  [completedRunId, { body: completed, redactionCount: 10 }],
  [failedRunId, { body: failed, redactionCount: 0 }],
  [abstainedRunId, { body: abstained, redactionCount: 0 }],
  [budgetRunId, { body: budgetExceeded, redactionCount: 0 }],
  [createdRunId, { body: created, redactionCount: 0 }],
  [analyzingRunId, { body: analyzing, redactionCount: 0 }],
  [waitingRunId, { body: waiting, redactionCount: 1 }],
  [noProgressRunId, { body: noProgress, redactionCount: 0 }],
]);

const server = createServer((request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    return sendJson(response, 200, { status: "ok" });
  }
  if (!authorized(request.headers.authorization)) {
    return sendProblem(response, 401, "Unauthorized");
  }
  if (request.method === "GET" && request.url === incidentPath) {
    return sendJson(response, 200, incident);
  }
  if (request.method !== "GET" || !request.url?.startsWith(investigationPath)) {
    return sendProblem(response, 404, "Not Found");
  }

  const runId = request.url.slice(investigationPath.length);
  const standard = standardRuns.get(runId);
  if (standard !== undefined) {
    return sendJson(response, 200, standard.body, true, standard.redactionCount);
  }

  switch (runId) {
    case unavailableRunId:
      return sendProblem(response, 503, "Service Unavailable");
    case unauthorizedRunId:
      return sendProblem(response, 401, "Unauthorized");
    case forbiddenRunId:
      return sendProblem(response, 403, "Forbidden");
    case missingRunId:
      return sendProblem(response, 404, "Not Found");
    case invalidRunId:
      return sendJson(response, 200, completedVariant(runId, {
        reasoning_content: "This prohibited field must reject the entire projection.",
      }), true, 10);
    case crossScopeRunId:
      return sendJson(response, 200, completedVariant(runId, {}, {
        organizationId: "20000000-0000-4000-8000-000000000702",
      }), true, 10);
    case oversizedRunId:
      return sendJson(response, 200, completedVariant(runId, {}, {
        padding: "x".repeat(600_000),
      }), true, 10);
    case uncitedRunId:
      return sendJson(response, 200, completedVariant(runId, {
        hypotheses: [{
          ...completed.analysis.hypotheses[0],
          citations: [],
        }, completed.analysis.hypotheses[1]],
      }), true, 10);
    case slowRunId:
      return setTimeout(() => sendJson(response, 200, slowCreated), 1_200);
    case timeoutRunId:
      return setTimeout(
        () => sendJson(response, 200, completedVariant(runId), true, 10),
        3_500,
      );
    case chunkedRunId:
      return sendChunkedOversize(response);
    case invalidMediaRunId:
      return sendRaw(response, 200, "text/plain", Buffer.from("{}"));
    case invalidJsonRunId:
      return sendRaw(response, 200, operatorProjectionMediaType, Buffer.from("{"));
    case invalidUtf8RunId:
      return sendRaw(response, 200, operatorProjectionMediaType, Buffer.from([0xff]));
    case unclassifiedRunId:
      return sendJson(response, 200, completedVariant(runId), false);
    case unknownOperationRunId:
      return sendJson(response, 200, {
        ...waiting,
        runId,
        pendingToolCalls: [{
          ...waiting.pendingToolCalls[0],
          operation: "execute",
        }],
      }, true, 1);
    default:
      return sendProblem(response, 404, "Not Found");
  }
});

server.listen(port, "127.0.0.1");

function authorized(header) {
  if (!header?.startsWith("Bearer ")) return false;
  const actual = Buffer.from(header.slice("Bearer ".length));
  const expected = Buffer.from(bearer);
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

function completedVariant(runId, analysisOverrides = {}, rootOverrides = {}) {
  return {
    ...completed,
    ...rootOverrides,
    runId,
    analysis: {
      ...completed.analysis,
      ...analysisOverrides,
      run_id: runId,
    },
  };
}

function sendProblem(response, status, title) {
  return sendJson(response, status, { type: "about:blank", title, status });
}

function sendJson(
  response,
  status,
  body,
  browserSafe = status >= 200 && status < 300,
  redactionCount = 0,
) {
  return sendRaw(
    response,
    status,
    status >= 200 && status < 300 ? operatorProjectionMediaType : "application/json",
    Buffer.from(JSON.stringify(body)),
    browserSafe,
    redactionCount,
  );
}

function sendRaw(response, status, mediaType, bytes, browserSafe = true, redactionCount = 0) {
  const headers = {
    "Content-Type": mediaType,
    "Content-Length": String(bytes.length),
    "Cache-Control": "no-store",
  };
  if (browserSafe) {
    headers["X-OpsMind-Projection-Class"] = "operator-browser-safe-v1";
    headers["X-OpsMind-Redaction-Version"] = "display-redaction-v1";
    headers["X-OpsMind-Redaction-Count"] = String(redactionCount);
  }
  response.writeHead(status, headers);
  response.end(bytes);
}

function sendChunkedOversize(response) {
  response.writeHead(200, {
    "Content-Type": operatorProjectionMediaType,
    "Cache-Control": "no-store",
    "X-OpsMind-Projection-Class": "operator-browser-safe-v1",
    "X-OpsMind-Redaction-Version": "display-redaction-v1",
    "X-OpsMind-Redaction-Count": "0",
  });
  response.write('{"padding":"');
  response.write("x".repeat(600_000));
  response.end('"}');
}

function shutdown() {
  server.close(() => process.exit(0));
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
