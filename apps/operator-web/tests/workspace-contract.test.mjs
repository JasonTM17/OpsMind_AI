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
  for (const command of ["build", "lint", "typecheck", "test"]) {
    assert.equal(typeof manifest.scripts[command], "string");
  }
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
