import {
  existsSync,
  linkSync,
  lstatSync,
  readFileSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { enrichTraceWithEvaluationExports } from "./cross-service-evaluation-projection.mjs";
import { parseUntrustedJsonExport } from "./evaluation-value-safety.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "../..");

function isWithin(candidate, parent) {
  const relative = path.relative(parent, candidate);
  return relative !== "" && !relative.startsWith("..") && !path.isAbsolute(relative);
}

function containsLink(candidate) {
  let current = path.resolve(candidate);
  while (true) {
    if (existsSync(current) && lstatSync(current).isSymbolicLink()) return true;
    const parent = path.dirname(current);
    if (parent === current) return false;
    current = parent;
  }
}

function assertSafeOutput(outputPath) {
  const configuredArtifactRoot = process.env.OPS_ARTIFACT_ROOT;
  if (configuredArtifactRoot && !path.isAbsolute(configuredArtifactRoot)) {
    throw new Error("OPS_ARTIFACT_ROOT must be absolute.");
  }
  const roots = [
    path.join(repositoryRoot, ".opsmind", "cross-service"),
    path.join(repositoryRoot, ".opsmind", "reports"),
    configuredArtifactRoot || path.join(repositoryRoot, "artifacts"),
  ].map((root) => path.resolve(root));
  if (!roots.some((root) => isWithin(outputPath, root))) {
    throw new Error("Projection output must remain in a managed report root.");
  }
  const directory = path.dirname(outputPath);
  if (!existsSync(directory) || !lstatSync(directory).isDirectory() || containsLink(directory)) {
    throw new Error("Projection output directory is missing or unsafe.");
  }
  if (existsSync(outputPath)) throw new Error("Projection output already exists.");
}

function publishExclusive(outputPath, transcript) {
  assertSafeOutput(outputPath);
  const temporaryPath = `${outputPath}.${process.pid}.${Date.now()}.tmp`;
  try {
    writeFileSync(temporaryPath, transcript, { encoding: "utf8", flag: "wx" });
    assertSafeOutput(outputPath);
    linkSync(temporaryPath, outputPath);
  } finally {
    if (existsSync(temporaryPath)) unlinkSync(temporaryPath);
  }
}

function parseArguments(argv) {
  const parsed = { exports: [] };
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (!value || !["--export", "--trace", "--output"].includes(flag)) {
      throw new Error("Usage: --trace <path> --output <path> --export <path> [--export <path> ...]");
    }
    if (flag === "--export") parsed.exports.push(path.resolve(value));
    else parsed[flag.slice(2)] = path.resolve(value);
  }
  if (!parsed.trace || !parsed.output || parsed.exports.length === 0) {
    throw new Error("Trace, output, and at least one export path are required.");
  }
  if (new Set(parsed.exports).size !== parsed.exports.length) {
    throw new Error("Duplicate export path.");
  }
  return parsed;
}

function traceReference(outputPath) {
  const relative = path.relative(repositoryRoot, outputPath).replaceAll(path.sep, "/");
  return relative !== "" && !relative.startsWith("../") && !path.isAbsolute(relative)
    ? `repository://${relative}`
    : `artifact://${path.basename(outputPath)}`;
}

export function runProjectionCli(argv) {
  const options = parseArguments(argv);
  const trace = parseUntrustedJsonExport(readFileSync(options.trace)).document;
  const exports = options.exports.map((filePath) => readFileSync(filePath));
  const enriched = enrichTraceWithEvaluationExports(
    trace,
    exports,
    traceReference(options.output),
  );
  publishExclusive(options.output, `${JSON.stringify(enriched, null, 2)}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    runProjectionCli(process.argv.slice(2));
  } catch (error) {
    const detail = error instanceof Error ? error.message : "unknown safe failure";
    console.error(`Evaluation projection failed: ${detail}`);
    process.exitCode = 1;
  }
}
