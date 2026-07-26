import fs from "node:fs";
import path from "node:path";
import {
  prepareValidationEvidence,
  publishValidationEvidence,
} from "../safe-validation-evidence.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "../../..");
const crossServiceRoot = path.resolve(repositoryRoot, ".opsmind/cross-service");
const reportRoot = path.resolve(repositoryRoot, ".opsmind/reports");
const maximumPublicationBytes = 64 * 1024 * 1024;

function within(candidate, parent) {
  const relative = path.relative(parent, candidate);
  return relative !== "" && !relative.startsWith("..") && !path.isAbsolute(relative);
}

function parseArguments(values) {
  const command = values.shift();
  if (!["prepare", "publish"].includes(command)) {
    throw new Error("managed evaluation file command must be prepare or publish");
  }
  const options = {};
  for (let index = 0; index < values.length; index += 2) {
    const option = values[index];
    const value = values[index + 1];
    if (!option?.startsWith("--") || !value || Object.hasOwn(options, option)) {
      throw new Error("managed evaluation file arguments are invalid");
    }
    options[option] = path.resolve(value);
  }
  return { command, options };
}

function prepare(managedRoot, candidatePath, environmentName) {
  if (
    ![crossServiceRoot, reportRoot].some(
      (allowedRoot) => managedRoot === allowedRoot || within(managedRoot, allowedRoot),
    )
  ) {
    throw new Error("managed root is outside the cross-service allowlist");
  }
  const evidence = prepareValidationEvidence({
    repositoryRoot,
    configuredArtifactRoot: managedRoot,
    configuredEvidencePath: candidatePath,
    defaultRelativePath: "unused",
    evidenceEnvironmentName: environmentName,
  });
  if (evidence.error || evidence.evidencePath !== candidatePath) {
    throw new Error(evidence.error ?? `${environmentName} is unavailable`);
  }
}

const { command, options } = parseArguments(process.argv.slice(2));
if (command === "prepare") {
  if (Object.keys(options).length !== 2) throw new Error("prepare requires two paths");
  const managedRoot = options["--managed-root"];
  const candidatePath = options["--path"];
  if (!managedRoot || !candidatePath) throw new Error("prepare paths are required");
  prepare(managedRoot, candidatePath, "OPSMIND_TRANSIENT_EVALUATION_PATH");
  if (fs.existsSync(candidatePath)) throw new Error("managed transient path already exists");
  process.stdout.write("ManagedEvaluationPath=PASS\n");
}
else {
  if (Object.keys(options).length !== 3) throw new Error("publish requires three paths");
  const source = options["--source"];
  const destination = options["--destination"];
  const managedRoot = options["--managed-root"];
  if (!source || !destination || !managedRoot) {
    throw new Error("publish paths are required");
  }
  prepare(managedRoot, destination, "OPSMIND_TRACE_REPORT");
  if (!within(source, crossServiceRoot)) {
    throw new Error("projection source must stay under the managed cross-service root");
  }
  const sourceStatus = fs.lstatSync(source);
  if (!sourceStatus.isFile() || sourceStatus.isSymbolicLink()) {
    throw new Error("projection source must be a regular non-link file");
  }
  if (sourceStatus.size < 2 || sourceStatus.size > maximumPublicationBytes) {
    throw new Error("projection source is empty or oversized");
  }
  publishValidationEvidence(destination, fs.readFileSync(source, "utf8"));
  process.stdout.write("ManagedEvaluationPublication=PASS\n");
}
