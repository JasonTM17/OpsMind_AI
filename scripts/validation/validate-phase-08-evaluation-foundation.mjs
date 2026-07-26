import { createHash } from "node:crypto";
import path from "node:path";

import { createEvaluationContractValidator } from "../../evaluation/runner/evaluation-contract-validation.mjs";
import { scorePhase07Trace } from "../../evaluation/runner/score-phase-07-trace-core.mjs";
import { createContractFileAccess } from "./phase-04-incident-contracts/safe-contract-files.mjs";
import { rejectDuplicateJsonKeys } from "./phase-04-incident-contracts/duplicate-json-key-detector.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "../..");
const errors = [];
const fileAccess = createContractFileAccess(repositoryRoot, errors);
const validate = createEvaluationContractValidator(repositoryRoot);

function readObject(relativePath) {
  const absolutePath = path.join(repositoryRoot, relativePath);
  const source = fileAccess.readSafeFile(absolutePath);
  rejectDuplicateJsonKeys(source);
  const document = JSON.parse(source);
  if (!document || typeof document !== "object" || Array.isArray(document)) {
    throw new Error(`JSON object required: ${relativePath}`);
  }
  return { absolutePath, document };
}

function sha256(filePath) {
  if (
    !fileAccess.isWithin(filePath, repositoryRoot)
    || fileAccess.hasSymlinkFromRoot(filePath)
  ) {
    throw new Error("evaluation fixture path escapes the repository or contains a symlink");
  }
  return `sha256:${createHash("sha256").update(fileAccess.readSafeFile(filePath)).digest("hex")}`;
}

function check(condition, message) {
  if (!condition) errors.push(message);
}

const truthPath = "evaluation/scenarios/deployment-latency-regression/ground-truth.json";
const truth = readObject(truthPath);
const truthFindings = validate(truth.document, "scenario-ground-truth.schema.json");
errors.push(...truthFindings.map((finding) => `ground truth ${finding}`));

const fixturePath = path.resolve(
  path.dirname(truth.absolutePath),
  truth.document.fixture_path,
);
check(
  fixturePath === path.join(path.dirname(truth.absolutePath), "fixture.json"),
  "ground truth fixture path must remain scenario-local",
);
check(
  sha256(fixturePath) === truth.document.fixture_digest,
  "ground truth fixture digest is stale",
);

const manifestPath = path.join(repositoryRoot, "evaluation", "benchmark-manifest.yaml");
const manifestSource = fileAccess.readSafeFile(manifestPath);
rejectDuplicateJsonKeys(manifestSource);
const manifest = JSON.parse(manifestSource);
const manifestFindings = validate(manifest, "benchmark-manifest.schema.json");
errors.push(...manifestFindings.map((finding) => `manifest ${finding}`));
check(manifest.schema_version === "opsmind-benchmark-manifest-v1", "manifest schema is invalid");
check(manifest.evidence_level === "deterministic-smoke", "manifest evidence level is invalid");
check(manifest.training_eligible === false, "benchmark corpus must be training-ineligible");
check(Array.isArray(manifest.scenario_families), "scenario family registry is missing");

const families = Array.isArray(manifest.scenario_families) ? manifest.scenario_families : [];
const expectedFamilyIds = Array.from({ length: 10 }, (_, index) => (
  `SIM-${String(index + 1).padStart(2, "0")}`
));
check(
  JSON.stringify(families.map((family) => family.family_id)) === JSON.stringify(expectedFamilyIds),
  "exactly ten ordered scenario families must be reserved",
);
check(
  new Set(families.map((family) => family.scenario_id)).size === families.length,
  "scenario identifiers must be unique",
);
const implemented = families.filter((family) => family.status === "implemented");
check(
  implemented.length === 1 && implemented[0]?.scenario_id === truth.document.scenario_id,
  "only Scenario A may be implemented in the Phase 8A checkpoint",
);
check(
  implemented[0]?.fixture_digest === truth.document.fixture_digest,
  "manifest and ground truth fixture digests differ",
);
check(
  implemented[0]?.ground_truth_path === truthPath,
  "manifest ground-truth path is not canonical",
);

const trace = readObject("evaluation/fixtures/phase-07-trace.scenario-a.valid.json");
const result = scorePhase07Trace({
  groundTruth: truth.document,
  trace: trace.document,
  traceReference: "repository://evaluation/fixtures/phase-07-trace.scenario-a.valid.json",
  generatedAt: "2030-01-01T00:07:00Z",
});
const resultFindings = validate(result, "benchmark-result.schema.json");
errors.push(...resultFindings.map((finding) => `benchmark result ${finding}`));
check(result.verdict === "PASS", "canonical Scenario A fixture must pass");
check(
  Object.values(result.metrics).every((metric) => metric.status === "PASS"),
  "canonical Scenario A metric set is incomplete",
);

const incompleteTrace = structuredClone(trace.document);
delete incompleteTrace.runs[0].operatorProjection;
delete incompleteTrace.runs[0].toolExecutions;
const incomplete = scorePhase07Trace({
  groundTruth: truth.document,
  trace: incompleteTrace,
  traceReference: "repository://evaluation/fixtures/phase-07-trace.scenario-a.valid.json",
  generatedAt: "2030-01-01T00:07:00Z",
});
check(incomplete.verdict === "INCOMPLETE", "missing raw artifacts must fail closed");

const invalidTruth = structuredClone(truth.document);
invalidTruth.unreviewed_field = true;
check(
  validate(invalidTruth, "scenario-ground-truth.schema.json").length > 0,
  "ground-truth schema must reject unknown fields",
);
const invalidResult = structuredClone(result);
invalidResult.raw_artifact_references = [];
check(
  validate(invalidResult, "benchmark-result.schema.json").length > 0,
  "benchmark-result schema must require raw artifact references",
);

const crossServiceRunner = fileAccess.readSafeFile(
  path.join(repositoryRoot, "scripts/validation/cross-service/run-investigation-slice.mjs"),
);
check(
  crossServiceRunner.includes("operatorProjection: read.parsed"),
  "Phase 7 trace must retain the bounded operator projection for scoring",
);
for (const launcher of ["scripts/dev/opsmind.ps1", "scripts/dev/opsmind.sh"]) {
  const source = fileAccess.readSafeFile(path.join(repositoryRoot, launcher));
  check(
    source.includes("validate-phase-08-evaluation-foundation.mjs")
      && source.includes("score-phase-07-trace.mjs"),
    `${launcher} does not wire the Phase 8 evaluation command`,
  );
}

console.log("Phase08EvaluationFoundation");
console.log(`ScenarioSchemas=3 ScenarioFamilies=${families.length} Implemented=${implemented.length}`);
console.log(`CanonicalMetrics=${Object.keys(result.metrics).length} NegativeCases=3`);
console.log(`Errors=${errors.length}`);
for (const error of errors) console.log(`Error=${error}`);
if (errors.length > 0) {
  console.log("CheckpointResult=BLOCK");
  process.exitCode = 1;
} else {
  console.log("CheckpointResult=PASS");
  console.log("PhaseExit=BLOCK");
}
