import { spawn } from "node:child_process";
import { cpSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const appRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const standaloneRoot = path.join(appRoot, ".next", "standalone");
const standaloneApp = path.join(standaloneRoot, "apps", "operator-web");
const webPort = process.env.OPSMIND_E2E_PRODUCTION_WEB_PORT ?? "3001";
const environment = {
  ...process.env,
  OPSMIND_DEPLOYMENT_ENVIRONMENT: "staging",
  OPSMIND_OPERATOR_AUTH_MODE: "disabled",
  OPSMIND_PLATFORM_API_URL: "https://platform.invalid",
  OPSMIND_PLATFORM_API_TIMEOUT_MS: "3000",
  HOSTNAME: "127.0.0.1",
  PORT: webPort,
};
delete environment.OPSMIND_OPERATOR_TEST_BEARER;

cpSync(
  path.join(appRoot, ".next", "static"),
  path.join(standaloneApp, ".next", "static"),
  { recursive: true, force: true },
);
const next = spawn(
  process.execPath,
  [path.join(standaloneApp, "server.js")],
  { cwd: standaloneRoot, env: environment, stdio: "inherit" },
);

let stopping = false;
function stop(exitCode = 0) {
  if (stopping) return;
  stopping = true;
  next.kill();
  setTimeout(() => process.exit(exitCode), 1_000).unref();
}

next.once("exit", (code, signal) => {
  if (!stopping) {
    process.stderr.write(`Next.js production server exited early (${code ?? signal}).\n`);
    stop(1);
  }
});
process.on("SIGINT", () => stop(0));
process.on("SIGTERM", () => stop(0));
process.on("exit", () => next.kill());
