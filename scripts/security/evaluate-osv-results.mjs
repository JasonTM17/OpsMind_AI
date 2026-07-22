import fs from "node:fs";
import path from "node:path";

const maximumReportBytes = 100 * 1024 * 1024;

function strictNumber(value, label) {
  if (
    (typeof value !== "string" && typeof value !== "number") ||
    (typeof value === "string" && value.trim() === "")
  ) {
    throw new Error(`${label} must be a finite number.`);
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) throw new Error(`${label} must be a finite number.`);
  return parsed;
}

function parseArguments(argumentsList) {
  const values = new Map();
  for (let index = 0; index < argumentsList.length; index += 2) {
    const flag = argumentsList[index];
    const value = argumentsList[index + 1];
    if (!flag?.startsWith("--") || value === undefined || values.has(flag)) {
      throw new Error(
        "Usage: node scripts/security/evaluate-osv-results.mjs --input <path> --threshold <0-10> --expected-source-count <positive-integer>",
      );
    }
    values.set(flag, value);
  }
  const allowedFlags = new Set(["--input", "--threshold", "--expected-source-count"]);
  if (values.size !== allowedFlags.size || [...values.keys()].some((flag) => !allowedFlags.has(flag))) {
    throw new Error("The OSV policy evaluator requires exactly input, threshold, and expected source count.");
  }
  const threshold = strictNumber(values.get("--threshold"), "The severity threshold");
  const expectedSourceCount = strictNumber(values.get("--expected-source-count"), "The expected source count");
  if (threshold < 0 || threshold > 10) {
    throw new Error("The severity threshold must be a finite number from 0 through 10.");
  }
  if (!Number.isSafeInteger(expectedSourceCount) || expectedSourceCount < 1) {
    throw new Error("The expected source count must be a positive integer.");
  }
  return { input: path.resolve(values.get("--input")), threshold, expectedSourceCount };
}

function readReport(input) {
  const item = fs.lstatSync(input);
  if (!item.isFile() || item.isSymbolicLink() || item.size < 1 || item.size > maximumReportBytes) {
    throw new Error(`OSV report must be a non-empty regular file no larger than ${maximumReportBytes} bytes.`);
  }
  return JSON.parse(fs.readFileSync(input, "utf8"));
}

function nonEmptyString(value, label) {
  if (typeof value !== "string" || value.trim() === "" || /[\u0000-\u001f\u007f]/.test(value)) {
    throw new Error(`${label} must be a non-empty single-line string.`);
  }
  return value.trim();
}

function evaluate(report, threshold, expectedSourceCount) {
  if (!report || !Array.isArray(report.results) || report.results.length !== expectedSourceCount) {
    throw new Error(`OSV report must contain exactly ${expectedSourceCount} result sources.`);
  }

  const sources = new Set();
  const findings = [];
  let packageCount = 0;
  for (const [resultIndex, result] of report.results.entries()) {
    const sourcePath = nonEmptyString(result?.source?.path, `results[${resultIndex}].source.path`);
    if (result?.source?.type !== "sbom") throw new Error(`OSV source is not an SBOM: ${sourcePath}`);
    if (sources.has(sourcePath)) throw new Error(`OSV source is duplicated: ${sourcePath}`);
    sources.add(sourcePath);
    if (!Array.isArray(result.packages) || result.packages.length === 0) {
      throw new Error(`OSV source has no package coverage: ${sourcePath}`);
    }

    packageCount += result.packages.length;
    for (const [packageIndex, packageResult] of result.packages.entries()) {
      const packageName = nonEmptyString(
        packageResult?.package?.name,
        `results[${resultIndex}].packages[${packageIndex}].package.name`,
      );
      const packageVersion = nonEmptyString(
        packageResult?.package?.version,
        `results[${resultIndex}].packages[${packageIndex}].package.version`,
      );
      const groups = packageResult.groups ?? [];
      if (!Array.isArray(groups)) throw new Error(`Vulnerability groups are invalid for ${packageName}@${packageVersion}.`);
      const vulnerabilities = packageResult.vulnerabilities ?? [];
      if (!Array.isArray(vulnerabilities)) {
        throw new Error(`Vulnerability details are invalid for ${packageName}@${packageVersion}.`);
      }
      if (vulnerabilities.length > 0 && groups.length === 0) {
        throw new Error(`Vulnerability severity groups are missing for ${packageName}@${packageVersion}.`);
      }
      for (const group of groups) {
        if (!Array.isArray(group?.ids) || group.ids.length === 0) {
          throw new Error(`A vulnerability group has no identifiers for ${packageName}@${packageVersion}.`);
        }
        const identifiers = [...new Set(group.ids.map((id) => nonEmptyString(id, "vulnerability id")))].sort();
        let severity;
        try {
          severity = strictNumber(group.max_severity, "Vulnerability severity");
        } catch {
          throw new Error(`A vulnerability group has unknown severity for ${packageName}@${packageVersion}.`);
        }
        if (severity < 0 || severity > 10) {
          throw new Error(`A vulnerability group has unknown severity for ${packageName}@${packageVersion}.`);
        }
        findings.push({ identifiers, packageName, packageVersion, severity, sourcePath });
      }
    }
  }

  findings.sort((left, right) =>
    right.severity - left.severity ||
    left.packageName.localeCompare(right.packageName) ||
    left.packageVersion.localeCompare(right.packageVersion) ||
    left.identifiers.join(",").localeCompare(right.identifiers.join(",")),
  );
  const blocking = findings.filter((finding) => finding.severity >= threshold);
  return { blocking, findings, packageCount, sourceCount: sources.size };
}

function run() {
  const { input, threshold, expectedSourceCount } = parseArguments(process.argv.slice(2));
  const result = evaluate(readReport(input), threshold, expectedSourceCount);
  console.log(`Sources=${result.sourceCount}`);
  console.log(`Packages=${result.packageCount}`);
  console.log(`VulnerabilityGroups=${result.findings.length}`);
  console.log(`BelowThreshold=${result.findings.length - result.blocking.length}`);
  console.log(`Blocking=${result.blocking.length}`);
  for (const finding of result.findings) {
    console.log(
      `Finding=${finding.packageName}@${finding.packageVersion};Severity=${finding.severity.toFixed(1)};Ids=${finding.identifiers.join(",")};Source=${finding.sourcePath}`,
    );
  }
  console.log(`Threshold=${threshold.toFixed(1)}`);
  console.log(`Result=${result.blocking.length === 0 ? "PASS" : "BLOCK"}`);
  if (result.blocking.length > 0) process.exitCode = 7;
}

try {
  run();
} catch (error) {
  console.error(`OSVPolicy=BLOCK Reason=${error.message}`);
  process.exitCode = 2;
}
