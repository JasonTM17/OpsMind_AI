import fs from "node:fs";

const [responsePath] = process.argv.slice(2);
if (!responsePath) {
  process.stderr.write("Prometheus response path is required.\n");
  process.exit(2);
}

const body = fs.readFileSync(responsePath);
if (body.length === 0 || body.length > 65_536) {
  throw new Error("Prometheus response violates the byte ceiling.");
}

const response = JSON.parse(body.toString("utf8"));
const result = response?.data?.result;
if (response?.status !== "success"
    || response?.data?.resultType !== "matrix"
    || !Array.isArray(result)
    || result.length !== 1) {
  throw new Error("Prometheus response envelope is invalid.");
}

const [series] = result;
const labels = series?.metric;
const labelNames = labels && typeof labels === "object"
  ? Object.keys(labels).sort()
  : [];
if (labelNames.join(",") !== "__name__,service"
    || labels.__name__ !== "opsmind:http_request_duration_seconds:synthetic"
    || labels.service !== "opsmind-api"
    || !Array.isArray(series.values)
    || series.values.length < 1
    || series.values.length > 10) {
  throw new Error("Prometheus series identity or bounds are invalid.");
}

let previous = -1;
for (const sample of series.values) {
  if (!Array.isArray(sample) || sample.length !== 2
      || !Number.isFinite(sample[0]) || sample[0] <= previous
      || typeof sample[1] !== "string"
      || !/^-?(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?$/u.test(sample[1])) {
    throw new Error("Prometheus sample is invalid.");
  }
  previous = sample[0];
}

process.stdout.write(
  `PrometheusLiveQuery=PASS\nSeries=${result.length}\nPoints=${series.values.length}\n`,
);
