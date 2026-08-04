import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";

const repositoryRoot = path.resolve(import.meta.dirname, "../..");
const operatorWebRoot = path.join(repositoryRoot, "apps", "operator-web");
const fixedLegacyVersion = "1.1.18";
const lockfile = readFileSync(path.join(repositoryRoot, "pnpm-lock.yaml"), "utf8")
  .replace(/\r\n/g, "\n");
const workspace = readFileSync(
  path.join(repositoryRoot, "pnpm-workspace.yaml"),
  "utf8",
);
const packagesSection = lockfile.slice(
  lockfile.indexOf("\npackages:\n"),
  lockfile.indexOf("\nsnapshots:\n"),
);
const lockedBraceVersions = [...packagesSection.matchAll(
  /^  brace-expansion@([0-9]+\.[0-9]+\.[0-9]+):$/gmu,
)].map((match) => match[1]);

assert.deepEqual(lockedBraceVersions, [fixedLegacyVersion, "5.0.9"]);
assert.doesNotMatch(lockfile, /brace-expansion@1\.1\.(?:16|17)/u);
assert.doesNotMatch(workspace, /patchedDependencies:|GHSA-mh99-v99m-4gvg/u);

const requireFromHere = createRequire(import.meta.url);
const eslintPackagePath = requireFromHere.resolve("eslint/package.json", {
  paths: [operatorWebRoot],
});
const requireFromEslint = createRequire(eslintPackagePath);
const minimatchPackagePath = requireFromEslint.resolve("minimatch/package.json");
const requireFromMinimatch = createRequire(minimatchPackagePath);
const bracePackagePath = requireFromMinimatch.resolve("brace-expansion/package.json");
const braceEntryPath = requireFromMinimatch.resolve("brace-expansion");
const bracePackage = requireFromMinimatch(bracePackagePath);
const expand = requireFromMinimatch("brace-expansion");
const minimatch = requireFromEslint("minimatch");

assert.equal(bracePackage.version, fixedLegacyVersion);
assert.equal(typeof expand, "function");
assert.deepEqual(expand("a{b,c}d"), ["abd", "acd"]);
assert.deepEqual(expand("{1..3}"), ["1", "2", "3"]);
assert.deepEqual(expand("${a,b}{c,d}"), ["${a,b}{c,d}"]);
assert.equal(minimatch("incident.json", "*.json"), true);

const defaultProbe = spawnSync(
  process.execPath,
  [
    "--max-old-space-size=96",
    "-e",
    [
      `const expand = require(${JSON.stringify(braceEntryPath)});`,
      "const output = expand('{a,b}'.repeat(50));",
      "const length = output.reduce((total, value) => total + value.length, 0);",
      "if (output.length === 0 || length === 0 || length > 4000000) process.exit(9);",
      "process.stdout.write(JSON.stringify({count: output.length, length}));",
    ].join(""),
  ],
  {
    encoding: "utf8",
    timeout: 20_000,
    windowsHide: true,
  },
);
assert.equal(
  defaultProbe.status,
  0,
  `default brace expansion safety probe failed: ${defaultProbe.stderr}`,
);
const defaultProbeResult = JSON.parse(defaultProbe.stdout);
assert.ok(defaultProbeResult.count > 0);
assert.ok(defaultProbeResult.length > 0);
assert.ok(defaultProbeResult.length <= 4_000_000);

console.log("BraceExpansionResolution=PASS");
console.log(`ResolvedVersion=${bracePackage.version}`);
console.log(`LockedVersions=${lockedBraceVersions.join(",")}`);
console.log(
  `DefaultBoundCount=${defaultProbeResult.count} `
  + `DefaultBoundCharacters=${defaultProbeResult.length}`,
);
