import { randomBytes } from "node:crypto";
import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const appRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const bearer = randomBytes(48).toString("base64url");
const webPort = process.env.OPSMIND_E2E_WEB_PORT ?? "3000";
const platformPort = process.env.OPSMIND_E2E_PLATFORM_PORT ?? "4100";
const environment = {
  ...process.env,
  OPSMIND_E2E_PLATFORM_PORT: platformPort,
  OPSMIND_OPERATOR_AUTH_MODE: "test",
  OPSMIND_OPERATOR_TEST_BEARER: bearer,
  OPSMIND_PLATFORM_API_URL: `http://127.0.0.1:${platformPort}`,
  OPSMIND_PLATFORM_API_ALLOW_LOOPBACK_CLEARTEXT: "true",
  OPSMIND_PLATFORM_API_TIMEOUT_MS: "3000",
};

const fixture = spawn(
  process.execPath,
  [path.join(appRoot, "tests/support/platform-fixture-server.mjs")],
  { cwd: appRoot, env: environment, stdio: "inherit" },
);
const next = spawn(
  process.execPath,
  [
    path.join(appRoot, "node_modules/next/dist/bin/next"),
    "dev",
    "--hostname",
    "127.0.0.1",
    "--port",
    webPort,
  ],
  { cwd: appRoot, env: environment, stdio: "inherit" },
);

let stopping = false;
function stop(exitCode = 0) {
  if (stopping) return;
  stopping = true;
  fixture.kill();
  next.kill();
  setTimeout(() => process.exit(exitCode), 1_000).unref();
}

fixture.once("exit", (code, signal) => {
  if (!stopping) {
    process.stderr.write(`Platform fixture exited early (${code ?? signal}).\n`);
    stop(1);
  }
});
next.once("exit", (code, signal) => {
  if (!stopping) {
    process.stderr.write(`Next.js exited early (${code ?? signal}).\n`);
    stop(1);
  }
});

process.on("SIGINT", () => stop(0));
process.on("SIGTERM", () => stop(0));
process.on("exit", () => {
  fixture.kill();
  next.kill();
});
