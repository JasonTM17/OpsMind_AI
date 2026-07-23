import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const manifestUrl = new URL("../package.json", import.meta.url);
const workspaceUrl = new URL("../../../pnpm-workspace.yaml", import.meta.url);
const lockfileUrl = new URL("../../../pnpm-lock.yaml", import.meta.url);

test("operator web manifest stays private and exposes required gates", async () => {
  const manifest = JSON.parse(await readFile(manifestUrl, "utf8"));

  assert.equal(manifest.name, "@opsmind/operator-web");
  assert.equal(manifest.private, true);
  for (const command of [
    "build",
    "lint",
    "typecheck",
    "test",
    "test:e2e",
    "test:e2e:production",
  ]) {
    assert.equal(typeof manifest.scripts[command], "string");
  }
  assert.equal(manifest.devDependencies["@playwright/test"], "1.61.1");
  assert.equal(manifest.devDependencies["@axe-core/playwright"], "4.12.1");
});

test("operator data path stays server-owned, bounded, and fail-closed", async () => {
  const session = await readFile(
    new URL("../lib/platform-api/operator-session.ts", import.meta.url),
    "utf8",
  );
  const transport = await readFile(
    new URL("../lib/platform-api/bounded-platform-fetch.ts", import.meta.url),
    "utf8",
  );
  const loader = await readFile(
    new URL("../lib/platform-api/load-investigation-workspace.ts", import.meta.url),
    "utf8",
  );

  assert.match(session, /mode !== "test" \|\| process\.env\.NODE_ENV === "production"/u);
  assert.doesNotMatch(session + transport + loader, /NEXT_PUBLIC_/u);
  assert.match(transport, /redirect: "error"/u);
  assert.match(transport, /MAXIMUM_RESPONSE_BYTES/u);
  assert.match(transport, /application\/vnd\.opsmind\.operator-projection\.v1\+json/u);
  assert.match(transport, /Authorization: `Bearer \$\{accessToken\}`/u);
  assert.match(transport, /operator-browser-safe-v1/u);
  assert.match(transport, /display-redaction-v1/u);
  assert.match(transport, /x-opsmind-redaction-count/u);
  assert.match(loader, /requestGroup\.abort\(\)/u);
  assert.match(loader, /projectionSafety/u);
  assert.doesNotMatch(loader, /console\.(?:log|debug|info|warn|error)/u);
  assert.doesNotMatch(loader, /tool-gateway|ai-runtime|prometheus/iu);
});

test("operator session behavior rejects the test adapter in production", async () => {
  const previous = {
    mode: process.env.OPSMIND_OPERATOR_AUTH_MODE,
    bearer: process.env.OPSMIND_OPERATOR_TEST_BEARER,
    nodeEnvironment: process.env.NODE_ENV,
  };
  const { getOperatorSessionCredential } = await import(
    "../lib/platform-api/operator-session.ts"
  );

  try {
    delete process.env.OPSMIND_OPERATOR_AUTH_MODE;
    assert.equal(getOperatorSessionCredential(), null);

    process.env.OPSMIND_OPERATOR_AUTH_MODE = "test";
    process.env.OPSMIND_OPERATOR_TEST_BEARER = "a".repeat(48);
    process.env.NODE_ENV = "production";
    assert.throws(
      () => getOperatorSessionCredential(),
      /configured operator session mode is not available/u,
    );
  } finally {
    restoreEnvironment("OPSMIND_OPERATOR_AUTH_MODE", previous.mode);
    restoreEnvironment("OPSMIND_OPERATOR_TEST_BEARER", previous.bearer);
    restoreEnvironment("NODE_ENV", previous.nodeEnvironment);
  }
});

test("platform client configuration rejects malformed and production cleartext URLs", async () => {
  const previous = {
    url: process.env.OPSMIND_PLATFORM_API_URL,
    timeout: process.env.OPSMIND_PLATFORM_API_TIMEOUT_MS,
    allowCleartext: process.env.OPSMIND_PLATFORM_API_ALLOW_LOOPBACK_CLEARTEXT,
    nodeEnvironment: process.env.NODE_ENV,
  };
  const { loadPlatformClientConfiguration } = await import(
    "../lib/platform-api/platform-client-configuration.ts"
  );

  try {
    process.env.OPSMIND_PLATFORM_API_URL = "not a URL";
    assert.throws(() => loadPlatformClientConfiguration(), /Invalid URL/u);

    process.env.OPSMIND_PLATFORM_API_URL = "http://127.0.0.1:4100";
    process.env.OPSMIND_PLATFORM_API_ALLOW_LOOPBACK_CLEARTEXT = "true";
    process.env.NODE_ENV = "production";
    assert.throws(
      () => loadPlatformClientConfiguration(),
      /must use HTTPS/u,
    );

    process.env.NODE_ENV = "test";
    const configuration = loadPlatformClientConfiguration();
    assert.equal(configuration.baseUrl.origin, "http://127.0.0.1:4100");
    assert.equal(configuration.timeoutMilliseconds, 3_000);
  } finally {
    restoreEnvironment("OPSMIND_PLATFORM_API_URL", previous.url);
    restoreEnvironment("OPSMIND_PLATFORM_API_TIMEOUT_MS", previous.timeout);
    restoreEnvironment(
      "OPSMIND_PLATFORM_API_ALLOW_LOOPBACK_CLEARTEXT",
      previous.allowCleartext,
    );
    restoreEnvironment("NODE_ENV", previous.nodeEnvironment);
  }
});

test("production investigation components exclude executable and reasoning fields", async () => {
  const parser = await readFile(
    new URL("../features/investigation/parse-investigation-analysis.ts", import.meta.url),
    "utf8",
  );
  const validation = await readFile(
    new URL("../features/investigation/contract-validation.ts", import.meta.url),
    "utf8",
  );
  const workspace = await readFile(
    new URL("../features/investigation/investigation-workspace.tsx", import.meta.url),
    "utf8",
  );
  const spine = await readFile(
    new URL("../features/investigation/evidence-spine.tsx", import.meta.url),
    "utf8",
  );

  assert.match(validation, /is not allowed/u);
  assert.doesNotMatch(workspace + spine, /reasoning_content|chain[_-]?of[_-]?thought/iu);
  assert.doesNotMatch(workspace + spine, /fetch\(|Authorization|NEXT_PUBLIC_/u);
});

test("investigation route has an accessible layout-shaped loading boundary", async () => {
  const loading = await readFile(
    new URL(
      "../app/organizations/[organizationId]/projects/[projectId]/incidents/[incidentId]/investigations/[runId]/loading.tsx",
      import.meta.url,
    ),
    "utf8",
  );

  assert.match(loading, /aria-busy="true"/u);
  assert.match(loading, /Loading the authorized investigation projection/u);
  assert.doesNotMatch(loading, /spinner|animate-spin/iu);
});

test("operator entry resolves its environment label at runtime", async () => {
  const entry = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");
  const environment = await readFile(
    new URL("../lib/operator-environment.ts", import.meta.url),
    "utf8",
  );

  assert.match(entry, /export const dynamic = "force-dynamic"/u);
  assert.match(environment, /OPSMIND_DEPLOYMENT_ENVIRONMENT/u);
  assert.doesNotMatch(environment, /NEXT_PUBLIC_/u);
});

test("production environment label is explicit and recognized", async () => {
  const previous = {
    deployment: process.env.OPSMIND_DEPLOYMENT_ENVIRONMENT,
    nodeEnvironment: process.env.NODE_ENV,
  };
  const { operatorEnvironmentLabel } = await import("../lib/operator-environment.ts");

  try {
    process.env.NODE_ENV = "production";
    delete process.env.OPSMIND_DEPLOYMENT_ENVIRONMENT;
    assert.throws(operatorEnvironmentLabel, /required in production/u);

    process.env.OPSMIND_DEPLOYMENT_ENVIRONMENT = "unexpected";
    assert.throws(operatorEnvironmentLabel, /required in production/u);

    process.env.OPSMIND_DEPLOYMENT_ENVIRONMENT = "staging";
    assert.equal(operatorEnvironmentLabel(), "Staging");
  } finally {
    restoreEnvironment("OPSMIND_DEPLOYMENT_ENVIRONMENT", previous.deployment);
    restoreEnvironment("NODE_ENV", previous.nodeEnvironment);
  }
});

test("production smoke executes the standalone output through Playwright", async () => {
  const [configuration, launcher, specification] = await Promise.all([
    readFile(new URL("../playwright.production.config.ts", import.meta.url), "utf8"),
    readFile(
      new URL("../tests/support/start-production-smoke-stack.mjs", import.meta.url),
      "utf8",
    ),
    readFile(
      new URL("../tests/e2e-production/fail-closed-production.spec.ts", import.meta.url),
      "utf8",
    ),
  ]);

  assert.match(configuration, /testDir: "\.\/tests\/e2e-production"/u);
  assert.match(configuration, /start-production-smoke-stack\.mjs/u);
  assert.match(launcher, /"\.next", "standalone"/u);
  assert.match(launcher, /"server\.js"/u);
  assert.match(specification, /Secure operator session unavailable/u);
  assert.match(specification, /access\[_-\]\?token/u);
});

test("workspace pins the patched sharp line used by Next.js", async () => {
  const workspace = await readFile(workspaceUrl, "utf8");
  const lockfile = await readFile(lockfileUrl, "utf8");
  const override = workspace.match(/^\s{2}sharp:\s+(\d+)\.(\d+)\.(\d+)$/m);

  assert.ok(override, "sharp override must remain explicit");
  const [, major, minor] = override.map(Number);
  assert.ok(major > 0 || minor >= 35, "sharp must remain at or above the patched 0.35 line");
  const version = override.slice(1).join(".");
  assert.match(workspace, new RegExp(`^  'sharp@${version}': true$`, "m"));
  assert.match(lockfile, new RegExp(`^  sharp@${version}:$`, "m"));
});

function restoreEnvironment(name, value) {
  if (value === undefined) {
    delete process.env[name];
  } else {
    process.env[name] = value;
  }
}
