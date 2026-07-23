import { readFileSync } from "node:fs";
import { request } from "node:https";
import { setTimeout as delay } from "node:timers/promises";

const probeUrl = new URL(process.env.OPSMIND_IDENTITY_PROBE_URL ?? "");
const caFile = process.env.OPSMIND_IDENTITY_PROBE_CA_FILE ?? "";
const runTag = process.argv.find((argument) =>
  argument.startsWith("--opsmind-cross-service-run-id="));
if (
  probeUrl.protocol !== "https:"
  || probeUrl.hostname !== "127.0.0.1"
  || probeUrl.pathname !== "/__opsmind/status"
  || !/^[0-9a-f]{32}$/u.test(runTag?.split("=")[1] ?? "")
) {
  throw new Error("fixture identity probe configuration is invalid");
}
const certificate = readFileSync(caFile);
const deadline = Date.now() + 90_000;

function probeOnce() {
  return new Promise((resolve) => {
    const probe = request(probeUrl, {
      ca: certificate,
      method: "GET",
      rejectUnauthorized: true,
      timeout: 2_000,
    });
    let responseBytes = 0;
    probe.on("response", (response) => {
      response.on("data", (chunk) => {
        responseBytes += chunk.length;
        if (responseBytes > 4_096) response.destroy();
      });
      response.on("end", () => resolve(
        response.statusCode === 200 && responseBytes <= 4_096,
      ));
      response.on("error", () => resolve(false));
    });
    probe.on("timeout", () => probe.destroy());
    probe.on("error", () => resolve(false));
    probe.end();
  });
}

let ready = false;
while (!ready && Date.now() < deadline) {
  ready = await probeOnce();
  if (!ready) await delay(250);
}
if (!ready) throw new Error("fixture identity did not become HTTPS-ready");
process.stdout.write("FixtureIdentityProbe=PASS\n");
