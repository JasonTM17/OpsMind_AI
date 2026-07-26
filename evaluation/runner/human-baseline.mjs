import fs from "node:fs";
import path from "node:path";

import { createEvaluationContractValidator } from "./evaluation-contract-validation.mjs";
import {
  contractFailure,
  parseUntrustedJsonExport,
  safeIdentifier,
} from "./evaluation-value-safety.mjs";

export const BASELINE_ROOT_ENVIRONMENT = "OPS_EVALUATION_HUMAN_BASELINE_ROOT";
const MAX_RECORDS = 20000;
const MAX_RECORD_BYTES = 64 * 1024;
const REQUIRED_REVIEWERS_PER_CASE = 2;
function isWithin(candidatePath, parentPath) {
  const relative = path.relative(parentPath, candidatePath);
  return relative !== "" && !relative.startsWith("..") && !path.isAbsolute(relative);
}

function unavailable(reason) {
  return { status: "UNAVAILABLE", reason, cases: [], recordCount: 0 };
}

/**
 * Read the human comparator, if one exists.
 *
 * Every metric otherwise reported measures the system against its own ground
 * truth, which cannot distinguish "did what the scenario said" from "helped
 * anyone". This gate is expected to be `UNAVAILABLE`: producing it needs
 * reviewer time, not code, and reporting it as absent is the honest state.
 */
export function resolveHumanBaseline({
  baselineRoot,
  knownCaseIds = null,
  readDirectory = null,
  repositoryRoot = path.resolve(import.meta.dirname, "../.."),
}) {
  const configuredRoot = typeof baselineRoot === "string" ? baselineRoot.trim() : "";
  if (configuredRoot === "") {
    return unavailable(`${BASELINE_ROOT_ENVIRONMENT} is not configured`);
  }
  if (!path.isAbsolute(configuredRoot)) {
    contractFailure("HUMAN_BASELINE_ROOT", `${BASELINE_ROOT_ENVIRONMENT} must be absolute.`);
  }
  const resolvedRoot = path.resolve(configuredRoot);
  if (!fs.existsSync(resolvedRoot) || !fs.lstatSync(resolvedRoot).isDirectory()) {
    contractFailure("HUMAN_BASELINE_ROOT", "Human baseline root is missing or is not a directory.");
  }

  const listing = readDirectory
    ? readDirectory(resolvedRoot)
    : fs.readdirSync(resolvedRoot, { withFileTypes: true })
      .filter((entry) => entry.isFile() && entry.name.endsWith(".json"))
      .map((entry) => entry.name);
  if (!Array.isArray(listing)) {
    contractFailure("HUMAN_BASELINE_PATH", "Human baseline directory listing is invalid.");
  }
  if (listing.length === 0) {
    return unavailable("no reviewer sessions have been recorded");
  }
  if (listing.length > MAX_RECORDS) {
    contractFailure("HUMAN_BASELINE_RECORD", "Human baseline record count is unbounded.");
  }
  const recordNames = listing.map((rawName) => {
    if (typeof rawName !== "string"
      || !rawName.endsWith(".json")
      || safeIdentifier(rawName) !== rawName) {
      contractFailure("HUMAN_BASELINE_PATH", "Human baseline record name is invalid.");
    }
    return rawName;
  });

  // The protocol promises a submission cannot carry incident narrative or
  // reviewer commentary, and that promise is only real if the schema is
  // enforced here. Without this the record shape is checked ad hoc and an
  // unknown free-text field passes straight through.
  const validate = createEvaluationContractValidator(repositoryRoot);

  const byCase = new Map();
  for (const name of recordNames.sort()) {
    const recordPath = path.resolve(resolvedRoot, name);
    if (!isWithin(recordPath, resolvedRoot)) {
      contractFailure("HUMAN_BASELINE_PATH", `Human baseline record escapes its root: ${name}.`);
    }
    const statistics = safeRecordStatistics(recordPath, name);
    if (statistics.isSymbolicLink()) {
      contractFailure("HUMAN_BASELINE_PATH", `Human baseline record is a link: ${name}.`);
    }
    if (statistics.size > MAX_RECORD_BYTES) {
      contractFailure("HUMAN_BASELINE_RECORD", `Human baseline record exceeds its bound: ${name}.`);
    }
    const record = parseUntrustedJsonExport(safeReadRecord(recordPath, name)).document;
    const findings = validate(record, "human-baseline-record.schema.json");
    if (findings.length > 0) {
      contractFailure("HUMAN_BASELINE_RECORD", `Human baseline record violates its contract: ${name}.`);
    }
    if (knownCaseIds && !knownCaseIds.has(record.case_id)) {
      contractFailure("HUMAN_BASELINE_RECORD", `Human baseline record names an unknown case: ${name}.`);
    }
    if (record.abstained === true && record.root_cause_label !== null) {
      contractFailure("HUMAN_BASELINE_RECORD", `Abstained record carries a root cause: ${name}.`);
    }
    if (record.abstained === false && record.root_cause_label === null) {
      contractFailure("HUMAN_BASELINE_RECORD", `Non-abstained record carries no root cause: ${name}.`);
    }
    const reviewers = byCase.get(record.case_id) ?? new Map();
    if (reviewers.has(record.reviewer_id)) {
      contractFailure(
        "HUMAN_BASELINE_RECORD",
        `Reviewer answered a case twice: ${safeIdentifier(record.case_id)}.`,
      );
    }
    reviewers.set(record.reviewer_id, record);
    byCase.set(record.case_id, reviewers);
  }

  const complete = [];
  for (const [caseId, reviewers] of byCase) {
    const records = [...reviewers.values()];
    if (records.length < REQUIRED_REVIEWERS_PER_CASE) continue;
    const labels = new Set(records.map((record) => record.root_cause_label));
    complete.push({
      caseId,
      reviewerCount: records.length,
      // Disagreement is reported rather than resolved away. A system judged
      // against an answer qualified operators dispute is judged against noise.
      disagreed: labels.size > 1,
      adjudicated: records.some((record) => record.adjudicated === true),
      abstentions: records.filter((record) => record.abstained === true).length,
      medianMinutes: median(records.map((record) => record.minutes_to_conclusion)),
    });
  }

  if (complete.length === 0) {
    return unavailable(
      `no case has the required ${REQUIRED_REVIEWERS_PER_CASE} independent reviewers`,
    );
  }
  const unadjudicated = complete.filter((entry) => entry.disagreed && !entry.adjudicated);
  if (unadjudicated.length > 0) {
    contractFailure(
      "HUMAN_BASELINE_ADJUDICATION",
      `Reviewer disagreement was not adjudicated: ${unadjudicated.map((entry) => entry.caseId).join(", ")}.`,
    );
  }
  return {
    status: "RESOLVED",
    reason: null,
    cases: complete.sort((left, right) => left.caseId.localeCompare(right.caseId)),
    recordCount: listing.length,
  };
}

function safeRecordStatistics(recordPath, name) {
  try {
    return fs.lstatSync(recordPath);
  }
  catch {
    contractFailure("HUMAN_BASELINE_PATH", `Human baseline record is unavailable: ${name}.`);
  }
}

function safeReadRecord(recordPath, name) {
  try {
    return fs.readFileSync(recordPath);
  }
  catch {
    contractFailure("HUMAN_BASELINE_PATH", `Human baseline record is unreadable: ${name}.`);
  }
}

function median(values) {
  const sorted = values.slice().sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  const value = sorted.length % 2 === 0
    ? (sorted[middle - 1] + sorted[middle]) / 2
    : sorted[middle];
  return Number(value.toFixed(3));
}
