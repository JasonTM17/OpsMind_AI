import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const evaluator = path.join(scriptDirectory, "evaluate-osv-results.mjs");

function packageResult(name, version, groups = []) {
  return { package: { ecosystem: "Maven", name, version }, groups };
}

function report(groups = []) {
  return {
    results: [
      {
        source: { path: "/workspace/platform-api/target/bom.json", type: "sbom" },
        packages: [packageResult("example:platform", "1.0.0", groups)],
      },
      {
        source: { path: "/workspace/tool-gateway/target/bom.json", type: "sbom" },
        packages: [packageResult("example:gateway", "1.0.0")],
      },
    ],
  };
}

function evaluate(contents, expectedSourceCount = 2) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "opsmind-osv-policy-"));
  const input = path.join(directory, "osv.json");
  try {
    fs.writeFileSync(input, typeof contents === "string" ? contents : JSON.stringify(contents));
    return spawnSync(
      process.execPath,
      [evaluator, "--input", input, "--threshold", "7", "--expected-source-count", String(expectedSourceCount)],
      { encoding: "utf8", timeout: 10_000, windowsHide: true },
    );
  } finally {
    fs.rmSync(directory, { force: true, recursive: true });
  }
}

test("passes when both SBOMs have dependency coverage and no findings", () => {
  const result = evaluate(report());
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /Sources=2/);
  assert.match(result.stdout, /Packages=2/);
  assert.match(result.stdout, /Result=PASS/);
});

test("reports a medium finding without blocking a CVSS 7 policy", () => {
  const result = evaluate(report([{ ids: ["GHSA-medium"], max_severity: "6.5" }]));
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /BelowThreshold=1/);
  assert.match(result.stdout, /Blocking=0/);
});

test("blocks a finding at the configured threshold", () => {
  const result = evaluate(report([{ ids: ["CVE-high"], max_severity: "7.0" }]));
  assert.equal(result.status, 7, result.stderr);
  assert.match(result.stdout, /Blocking=1/);
  assert.match(result.stdout, /Result=BLOCK/);
});

test("fails closed when a vulnerability severity is unknown", () => {
  for (const maxSeverity of [undefined, null, false, "", "not-a-number"]) {
    const result = evaluate(report([{ ids: ["GHSA-unknown"], max_severity: maxSeverity }]));
    assert.equal(result.status, 2);
    assert.match(result.stderr, /unknown severity/);
  }
});

test("fails closed when vulnerability details have no severity group", () => {
  const input = report();
  input.results[0].packages[0].vulnerabilities = [{ id: "GHSA-hidden" }];
  const result = evaluate(input);
  assert.equal(result.status, 2);
  assert.match(result.stderr, /severity groups are missing/);
});

test("fails closed when source coverage is incomplete", () => {
  const result = evaluate(report(), 3);
  assert.equal(result.status, 2);
  assert.match(result.stderr, /exactly 3 result sources/);
});

test("fails closed on malformed scanner output", () => {
  const result = evaluate("{not-json");
  assert.equal(result.status, 2);
  assert.match(result.stderr, /OSVPolicy=BLOCK/);
});
