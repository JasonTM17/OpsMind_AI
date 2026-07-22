import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const installer = path.join(scriptDirectory, "install-pinned-osv-scanner.mjs");

function run(cacheRoot) {
  return spawnSync(process.execPath, [installer, "--cache-root", cacheRoot], {
    encoding: "utf8",
    timeout: 10_000,
    windowsHide: true,
  });
}

test("rejects a relative cache root", () => {
  const result = run("relative-cache");
  assert.equal(result.status, 2);
  assert.match(result.stderr, /must be an absolute path/);
});

test("rejects a tampered cached executable before running it", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "opsmind-osv-installer-"));
  const directory = path.join(root, "tools", "osv-scanner", "2.4.0");
  const executable = path.join(directory, process.platform === "win32" ? "osv-scanner.exe" : "osv-scanner");
  try {
    fs.mkdirSync(directory, { recursive: true });
    fs.writeFileSync(executable, "tampered");
    const result = run(root);
    assert.equal(result.status, 2);
    assert.match(result.stderr, /checksum mismatch/);
  } finally {
    fs.rmSync(root, { force: true, recursive: true });
  }
});
