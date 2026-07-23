import { createServer } from "node:http";

const host = process.env.OPSMIND_PROMETHEUS_HOST ?? "127.0.0.1";
if (host !== "127.0.0.1") throw new Error("fixture Prometheus must bind exact loopback");
const port = Number.parseInt(process.env.OPSMIND_PROMETHEUS_PORT ?? "19091", 10);
if (!Number.isInteger(port) || port < 1024 || port > 65535) {
  throw new Error("fixture Prometheus port is invalid");
}

const expectedQuery =
  'opsmind:http_request_duration_seconds:synthetic{service="opsmind-api"}';
const stats = { query_requests: 0, ready_requests: 0 };

function sendJson(response, status, document) {
  const body = Buffer.from(JSON.stringify(document));
  response.writeHead(status, {
    "Cache-Control": "no-store",
    "Content-Length": body.length,
    "Content-Type": "application/json",
  });
  response.end(body);
}

const server = createServer((request, response) => {
  const url = new URL(request.url, `http://${host}:${port}`);
  if (request.method === "GET" && url.pathname === "/-/ready") {
    stats.ready_requests += 1;
    response.writeHead(200, { "Content-Length": 5, "Content-Type": "text/plain" });
    response.end("ready");
    return;
  }
  if (request.method === "GET" && url.pathname === "/__opsmind/status") {
    sendJson(response, 200, {
      schema: "opsmind-fixture-prometheus-status-v1",
      ...stats,
    });
    return;
  }
  if (request.method !== "GET" || url.pathname !== "/api/v1/query_range") {
    response.writeHead(404, { "Content-Length": 0 });
    response.end();
    return;
  }
  const start = Number(url.searchParams.get("start"));
  const end = Number(url.searchParams.get("end"));
  const step = Number(url.searchParams.get("step"));
  if (
    url.searchParams.get("query") !== expectedQuery
    || !Number.isFinite(start)
    || !Number.isFinite(end)
    || !Number.isFinite(step)
    || start >= end
    || step <= 0
  ) {
    sendJson(response, 400, { status: "error", errorType: "bad_data", error: "invalid query" });
    return;
  }
  stats.query_requests += 1;
  sendJson(response, 200, {
    status: "success",
    data: {
      resultType: "matrix",
      result: [
        {
          metric: {
            __name__: "opsmind:http_request_duration_seconds:synthetic",
            service: "opsmind-api",
          },
          values: [
            [start, "0.42"],
            [Math.min(start + step, end), "0.87"],
            [end, "1.31"],
          ],
        },
      ],
    },
  });
});

server.listen(port, host, () => {
  process.stdout.write(`FixturePrometheus=READY Origin=http://${host}:${port}\n`);
});
