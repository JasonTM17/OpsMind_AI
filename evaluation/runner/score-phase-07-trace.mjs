import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { existsSync, lstatSync, readFileSync } from "node:fs";
import path from "node:path";

import { rejectDuplicateJsonKeys } from "../../scripts/validation/phase-04-incident-contracts/duplicate-json-key-detector.mjs";
import {
  prepareValidationEvidence,
  publishValidationEvidence,
} from "../../scripts/validation/safe-validation-evidence.mjs";
import { createEvaluationContractValidator } from "./evaluation-contract-validation.mjs";
import { scorePhase07Trace } from "./score-phase-07-trace-core.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "../..");
const artifactRoot = path.resolve(
  process.env.OPS_ARTIFACT_ROOT || path.join(repositoryRoot, "artifacts"),
);
const managedTraceRoots = [
  path.join(repositoryRoot, ".opsmind", "reports"),
  artifactRoot,
].map((value) => path.resolve(value));

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

function readJson(filePath, label) {
  const resolved = path.resolve(filePath);
  if (!existsSync(resolved) || !lstatSync(resolved).isFile() || containsLink(resolved)) {
    throw new Error(`${label} is missing or unsafe`);
  }
  const source = readFileSync(resolved, "utf8");
  rejectDuplicateJsonKeys(source);
  const document = JSON.parse(source);
  if (!document || typeof document !== "object" || Array.isArray(document)) {
    throw new Error(`${label} root must be an object`);
  }
  return { document, resolved };
}

function safeTracePath() {
  const configured = process.env.OPSMIND_EVALUATION_TRACE_PATH;
  const resolved = path.resolve(
    configured || path.join(repositoryRoot, ".opsmind", "reports", "cross-service-trace.json"),
  );
  if (!managedTraceRoots.some((root) => isWithin(resolved, root))) {
    throw new Error("OPSMIND_EVALUATION_TRACE_PATH must remain in a managed report root");
  }
  return resolved;
}

function digest(filePath) {
  return `sha256:${createHash("sha256").update(readFileSync(filePath)).digest("hex")}`;
}

function main() {
  const trace = readJson(safeTracePath(), "cross-service trace");
  const scenario = trace.document.scenario ?? "A";
  const scenarioDirectories = {
    A: "deployment-latency-regression",
    B: "insufficient-evidence-abstain",
    C: "conflicting-evidence-regression",
  };
  const scenarioDirectory = scenarioDirectories[scenario];
  if (!scenarioDirectory) {
    throw new Error("cross-service trace scenario must be A, B, or C");
  }
  const groundTruthPath = path.join(
    repositoryRoot,
    "evaluation",
    "scenarios",
    scenarioDirectory,
    "ground-truth.json",
  );
  const truth = readJson(groundTruthPath, "scenario ground truth").document;
  const validate = createEvaluationContractValidator(repositoryRoot);
  const truthFindings = validate(truth, "scenario-ground-truth.schema.json");
  if (truthFindings.length > 0) {
    throw new Error(`Scenario contract is invalid: ${truthFindings.join("; ")}`);
  }
  if (truth.scenario_id !== scenarioDirectory) {
    throw new Error("trace scenario and ground-truth identity differ");
  }
  const fixturePath = path.resolve(path.dirname(groundTruthPath), truth.fixture_path);
  if (!isWithin(fixturePath, path.dirname(groundTruthPath)) || containsLink(fixturePath)) {
    throw new Error("Scenario fixture path is unsafe");
  }
  if (digest(fixturePath) !== truth.fixture_digest) {
    throw new Error("Scenario fixture digest does not match ground truth");
  }

  if (
    trace.document.schema !== "opsmind-cross-service-trace-v1"
    || !Array.isArray(trace.document.runs)
    || !trace.document.source
  ) {
    throw new Error("cross-service trace schema or provenance envelope is invalid");
  }
  const currentHead = execFileSync("git", ["rev-parse", "HEAD"], {
    cwd: repositoryRoot,
    encoding: "utf8",
  }).trim();
  const currentStatus = execFileSync(
    "git",
    ["status", "--porcelain=v1", "--untracked-files=all"],
    { cwd: repositoryRoot, encoding: "utf8" },
  ).trim();
  if (
    trace.document.source?.gitHead !== currentHead
    || trace.document.source?.workingTreeClean !== true
    || currentStatus.length > 0
  ) {
    throw new Error(
      "cross-service trace is stale or was generated from a dirty worktree; "
        + "run the Phase 7 trace again at the current revision",
    );
  }
  const relativeTrace = path.relative(repositoryRoot, trace.resolved).replaceAll(path.sep, "/");
  const traceReference = relativeTrace.startsWith("..")
    ? `artifact://${path.basename(trace.resolved)}`
    : `repository://${relativeTrace}`;
  const result = scorePhase07Trace({
    groundTruth: truth,
    trace: trace.document,
    traceReference,
  });
  const resultFindings = validate(result, "benchmark-result.schema.json");
  if (resultFindings.length > 0) {
    throw new Error(`Benchmark result contract is invalid: ${resultFindings.join("; ")}`);
  }

  const timestamp = result.generated_at.replaceAll(/[-:.]/gu, "").replace("Z", "Z");
  const prepared = prepareValidationEvidence({
    repositoryRoot,
    configuredArtifactRoot: process.env.OPS_ARTIFACT_ROOT,
    configuredEvidencePath: process.env.OPSMIND_EVALUATION_OUTPUT_PATH,
    defaultRelativePath: `evaluation/phase-08/benchmark-result-${timestamp}.json`,
    evidenceEnvironmentName: "OPSMIND_EVALUATION_OUTPUT_PATH",
  });
  if (prepared.error) throw new Error(prepared.error);
  if (!prepared.evidencePath) throw new Error("Evaluation artifact root is unavailable");
  publishValidationEvidence(
    prepared.evidencePath,
    `${JSON.stringify(result, null, 2)}\n`,
  );
  process.stdout.write(
    `EvaluationVerdict=${result.verdict} Samples=${result.sample_count} `
      + `Report=${prepared.evidencePath}\n`,
  );
  if (result.verdict !== "PASS") process.exitCode = 4;
}

try {
  main();
} catch (error) {
  console.error(error instanceof Error ? error.message : "Evaluation failed safely.");
  process.exitCode = 1;
}
