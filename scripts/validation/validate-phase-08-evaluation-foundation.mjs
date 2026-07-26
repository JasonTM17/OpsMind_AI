import { createHash } from "node:crypto";
import path from "node:path";

import { createEvaluationContractValidator } from "../../evaluation/runner/evaluation-contract-validation.mjs";
import { projectCrossServiceEvaluationExport } from "../../evaluation/runner/cross-service-evaluation-projection.mjs";
import { scorePhase07Trace } from "../../evaluation/runner/score-phase-07-trace-core.mjs";
import {
  PAYLOAD_ROOT_ENVIRONMENT,
  resolveHeldOutCorpus,
} from "../../evaluation/runner/held-out-corpus.mjs";
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

function checkLfOnly(relativePath) {
  const source = fileAccess.readSafeFile(path.join(repositoryRoot, relativePath));
  check(!source.includes("\r"), `${relativePath} must use LF-only digest bytes`);
}

const attributesSource = fileAccess.readSafeFile(
  path.join(repositoryRoot, ".gitattributes"),
);
for (const rule of [
  "evaluation/**/*.json text eol=lf",
  "evaluation/**/*.yaml text eol=lf",
  "services/tool-gateway/src/main/resources/tool-manifests/*.json text eol=lf",
  "services/**/src/main/resources/db/migration/*.sql text eol=lf",
  "scripts/validation/cross-service/*.sql text eol=lf",
]) {
  check(attributesSource.includes(rule), `.gitattributes is missing byte contract: ${rule}`);
}

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
  JSON.stringify(implemented.map((family) => family.family_id))
    === JSON.stringify(["SIM-01", "SIM-02", "SIM-03"]),
  "only the three Phase 8B deterministic smoke families may be implemented",
);

const traceByFamily = {
  "SIM-01": "evaluation/fixtures/phase-07-trace.scenario-a.valid.json",
  "SIM-02": "evaluation/fixtures/phase-07-trace.scenario-b.valid.json",
  "SIM-03": "evaluation/fixtures/phase-07-trace.scenario-c.valid.json",
};
const scenarioByFamily = { "SIM-01": "A", "SIM-02": "B", "SIM-03": "C" };
const groundTruths = new Map();
const traces = new Map();
const results = [];
for (const family of implemented) {
  const truth = readObject(family.ground_truth_path);
  groundTruths.set(family.family_id, truth);
  const truthFindings = validate(truth.document, "scenario-ground-truth.schema.json");
  errors.push(...truthFindings.map((finding) => `${family.family_id} ground truth ${finding}`));
  const fixturePath = path.resolve(path.dirname(truth.absolutePath), truth.document.fixture_path);
  const expectedFixturePath = path.join(path.dirname(truth.absolutePath), "fixture.json");
  check(fixturePath === expectedFixturePath, `${family.family_id} fixture path is not scenario-local`);
  checkLfOnly(path.relative(repositoryRoot, fixturePath));
  check(sha256(fixturePath) === truth.document.fixture_digest, `${family.family_id} fixture digest is stale`);
  check(
    family.fixture_digest === truth.document.fixture_digest
      && path.resolve(repositoryRoot, family.fixture_path) === fixturePath
      && family.scenario_id === truth.document.scenario_id,
    `${family.family_id} manifest binding differs from ground truth`,
  );

  const tracePath = traceByFamily[family.family_id];
  const trace = readObject(tracePath);
  traces.set(family.family_id, trace);
  check(
    trace.document.evidenceClassification === "REGRESSION_SNAPSHOT_NOT_PRODUCTION_PATH",
    `${family.family_id} committed trace must be labeled as regression-only`,
  );
  check(
    trace.document.scenario === scenarioByFamily[family.family_id],
    `${family.family_id} regression trace scenario binding is invalid`,
  );
  const result = scorePhase07Trace({
    groundTruth: truth.document,
    trace: trace.document,
    traceReference: `repository://${tracePath}`,
    generatedAt: "2030-01-10T00:00:00Z",
  });
  results.push(result);
  const resultFindings = validate(result, "benchmark-result.schema.json");
  errors.push(...resultFindings.map((finding) => `${family.family_id} benchmark result ${finding}`));
  check(result.verdict === "PASS", `${family.family_id} canonical regression fixture must pass`);
  check(
    Object.values(result.metrics).every((metric) => metric.status === "PASS"),
    `${family.family_id} canonical metric set is incomplete`,
  );
}

const truth = groundTruths.get("SIM-01");
const trace = traces.get("SIM-01");
const result = results[0];

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

const exportFixturePath = "evaluation/fixtures/phase-08b-export.scenario-a.regression.json";
const exportFixture = readObject(exportFixturePath);
errors.push(...validate(
  exportFixture.document,
  "cross-service-evaluation-export.schema.json",
).map((finding) => `cross-service export ${finding}`));
try {
  const projection = projectCrossServiceEvaluationExport(
    fileAccess.readSafeFile(exportFixture.absolutePath),
  );
  check(
    projection.schemaVersion === "opsmind-cross-service-evaluation-projection-v1",
    "canonical cross-service export projection is invalid",
  );
} catch (error) {
  errors.push(`canonical cross-service export rejected: ${error.message}`);
}

const crossServiceRunner = fileAccess.readSafeFile(
  path.join(repositoryRoot, "scripts/validation/cross-service/run-investigation-slice.mjs"),
);
for (const relativePath of [
  "evaluation/benchmark-manifest.yaml",
  "services/tool-gateway/src/main/resources/tool-manifests/observability-metrics-query-v1.json",
  "services/tool-gateway/src/main/resources/tool-manifests/observability-metrics-query-prometheus-v1.json",
  "scripts/validation/cross-service/cross-service-evaluation-export.sql",
]) {
  checkLfOnly(relativePath);
}
check(
  crossServiceRunner.includes("operatorProjection: read.parsed")
    && crossServiceRunner.includes("evaluationProjection: null")
    && crossServiceRunner.includes('OPSMIND_CROSS_SERVICE_SCENARIO'),
  "cross-service runner does not declare the Phase 8B scenario/projection contract",
);
for (const [relativePath, markers] of Object.entries({
  "scripts/validation/cross-service/create-evaluation-export-roles.sql": [
    "NOBYPASSRLS", "security_barrier", "opsmind_evaluator",
    "GRANT EXECUTE ON FUNCTION public.opsmind_current_tenant_id()\n"
      + "    TO opsmind_evaluation_view_owner, opsmind_evaluator",
    "GRANT EXECUTE ON FUNCTION ai_runtime.current_tenant_id()\n"
      + "    TO opsmind_evaluation_view_owner, opsmind_evaluator",
    "'opsmind_evaluator', 'public.opsmind_current_tenant_id()', 'EXECUTE'",
    "'opsmind_evaluator', 'ai_runtime.current_tenant_id()', 'EXECUTE'",
  ],
  "scripts/validation/cross-service/cross-service-evaluation-export.sql": [
    "READ ONLY", "unmatched_accepted_count", "4194304",
  ],
  "scripts/validation/cross-service/run-cross-service-verification.ps1": [
    "project-cross-service-evaluation-export.mjs", "connectorManifestByteDigest", "Scenario",
    "Test-CrossServiceWindows", "check-capacity.ps1", "assert-storage-roots.ps1",
    "check-capacity.sh", "assert-storage-roots.sh", "--create-missing",
  ],
  ".github/workflows/cross-service-evaluation.yml": [
    "set +e", 'harness_pipeline_status=("${PIPESTATUS[@]}")',
    'scorer_pipeline_status=("${PIPESTATUS[@]}")', "HarnessTranscriptExit",
    "ScorerTranscriptExit", "overall=1", 'exit "$overall"',
  ],
  "services/platform-api/src/main/resources/db/migration/V008__accepted_analysis_event_binding.sql": [
    "opsmind_valid_accepted_analysis_response", "'response'", "run_row.final_response",
  ],
})) {
  const source = fileAccess.readSafeFile(path.join(repositoryRoot, relativePath));
  for (const marker of markers) {
    check(source.includes(marker), `${relativePath} is missing Phase 8B marker: ${marker}`);
  }
}
// The preregistration fixes the unit of analysis, sample size, and interval
// method before results are seen. Binding its digest here does not prevent an
// edit; it makes an unaccompanied one fail rather than pass unnoticed.
const preregistration = manifest.statistical_analysis_plan ?? {};
const preregistrationPath = path.join(repositoryRoot, preregistration.path ?? "");
const preregistrationBytes = Buffer.from(
  fileAccess.readSafeFile(preregistrationPath).replaceAll("\r\n", "\n"),
  "utf8",
);
const preregistrationDigest = `sha256:${createHash("sha256").update(preregistrationBytes).digest("hex")}`;
check(
  preregistrationDigest === preregistration.content_digest,
  "statistical analysis plan digest is stale",
);

// The held-out corpus is the only evidence about cases the system was not built
// against, so an empty or unconfigured corpus must report as an absence here
// rather than being skipped and read later as coverage.
const heldOutManifestPath = path.join(repositoryRoot, manifest.held_out_manifest_path ?? "");
const heldOutSource = fileAccess.readSafeFile(heldOutManifestPath);
rejectDuplicateJsonKeys(heldOutSource);
const heldOutManifest = JSON.parse(heldOutSource);
errors.push(
  ...validate(heldOutManifest, "held-out-manifest.schema.json")
    .map((finding) => `held-out manifest ${finding}`),
);
let heldOutCorpus = { status: "BLOCKED", reason: "resolution failed", cases: [] };
try {
  heldOutCorpus = resolveHeldOutCorpus({
    manifestBytes: Buffer.from(heldOutSource, "utf8"),
    payloadRoot: process.env[PAYLOAD_ROOT_ENVIRONMENT] ?? "",
    knownFamilyIds: new Set(families.map((family) => family.family_id)),
  });
}
catch (error) {
  // Reported through the same channel as every other finding so a corpus
  // failure stays machine-readable in a transcript instead of arriving as an
  // unhandled stack trace.
  heldOutCorpus = { status: "BLOCKED", reason: error.code ?? "unknown", cases: [] };
  check(false, `held-out corpus is unusable: ${error.message}`);
}
check(
  heldOutCorpus.status === "UNAVAILABLE" || heldOutCorpus.cases.length > 0,
  "held-out corpus resolved without any scorable case",
);

// A scenario bounds cost at its token budget priced at the rate the harness
// configures. Nothing else ties the two together, so a price change would
// silently make every cost budget wrong in whichever direction it moved.
const harnessSource = fileAccess.readSafeFile(
  path.join(repositoryRoot, "scripts/validation/cross-service/run-cross-service-verification.ps1"),
);
const configuredPrices = ["AI_INPUT_COST_USD_PER_MILLION", "AI_OUTPUT_COST_USD_PER_MILLION"]
  .map((name) => harnessSource.match(new RegExp(`${name}\\s*=\\s*'([0-9.]+)'`, "u"))?.[1]);
check(
  configuredPrices.every((price) => price !== undefined),
  "cross-service harness does not state both token prices",
);
if (configuredPrices.every((price) => price !== undefined)) {
  const perToken = Math.max(...configuredPrices.map(Number)) / 1_000_000;
  for (const [familyId, truth] of groundTruths) {
    const { max_tokens: maxTokens, max_cost_usd: maxCost } = truth.document.budgets;
    const expected = Number((maxTokens * perToken).toFixed(10));
    check(
      maxCost === expected,
      `${familyId} cost budget ${maxCost} does not price its ${maxTokens} token budget at ${expected}`,
    );
  }
}

for (const launcher of ["scripts/dev/opsmind.ps1", "scripts/dev/opsmind.sh"]) {
  const source = fileAccess.readSafeFile(path.join(repositoryRoot, launcher));
  check(
    source.includes("validate-phase-08-evaluation-foundation.mjs")
      && source.includes("score-phase-07-trace.mjs"),
    `${launcher} does not wire the Phase 8 evaluation command`,
  );
}

console.log("Phase08EvaluationFoundation");
console.log(`ScenarioSchemas=6 ScenarioFamilies=${families.length} Implemented=${implemented.length}`);
console.log(
  `HeldOutCorpus=${heldOutCorpus.status} HeldOutCases=${heldOutCorpus.cases.length}`
    + ` HeldOutReason=${heldOutCorpus.reason ?? "none"}`,
);
console.log(`CanonicalResults=${results.length} CanonicalMetrics=${Object.keys(result.metrics).length} NegativeCases=4`);
console.log(`Errors=${errors.length}`);
for (const error of errors) console.log(`Error=${error}`);
if (errors.length > 0) {
  console.log("CheckpointResult=BLOCK");
  process.exitCode = 1;
} else {
  console.log("CheckpointResult=PASS");
  console.log("PhaseExit=BLOCK");
}
