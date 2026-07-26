import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";

import { createEvaluationContractValidator } from "./evaluation-contract-validation.mjs";
import { contractFailure, parseUntrustedJsonExport } from "./evaluation-value-safety.mjs";

export const PAYLOAD_ROOT_ENVIRONMENT = "OPS_EVALUATION_HELDOUT_ROOT";
const MAX_CASES = 2000;
const MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;
const SAFE_IDENTIFIER = /^[A-Za-z0-9._-]{1,128}$/u;

// A manifest identifier reaches redacted evidence transcripts through failure
// messages. Restricting it to a printable, single line shape keeps a crafted
// identifier from forging transcript lines.
function safeName(value) {
  return SAFE_IDENTIFIER.test(String(value ?? "")) ? String(value) : "[unsafe name]";
}

function isWithin(candidatePath, parentPath) {
  const relative = path.relative(parentPath, candidatePath);
  return relative !== "" && !relative.startsWith("..") && !path.isAbsolute(relative);
}

function hasLinkedAncestor(rootPath, candidatePath) {
  let current = rootPath;
  for (const segment of path.relative(rootPath, candidatePath).split(path.sep).filter(Boolean)) {
    current = path.join(current, segment);
    if (!fs.existsSync(current)) return false;
    if (fs.lstatSync(current).isSymbolicLink()) return true;
  }
  return false;
}

function unavailable(reason) {
  return { status: "UNAVAILABLE", reason, cases: [] };
}

/**
 * Resolve the held-out corpus described by a manifest.
 *
 * An unconfigured root or an empty manifest is an absence of evidence and is
 * reported as `UNAVAILABLE` with its reason. A configured corpus whose payloads
 * are missing, altered, oversized, or reachable only through a link is a
 * contract failure, because a corpus that silently shrinks would otherwise read
 * as a corpus that passed.
 */
export function resolveHeldOutCorpus({
  manifestBytes,
  payloadRoot,
  knownFamilyIds = null,
  repositoryRoot = path.resolve(import.meta.dirname, "../.."),
}) {
  const manifest = parseUntrustedJsonExport(manifestBytes).document;
  if (manifest.schema_version !== "opsmind-held-out-manifest-v1") {
    contractFailure("HELD_OUT_MANIFEST", "Held-out manifest schema version is unsupported.");
  }
  // Applied here as well as in the validator. A contract enforced in only one
  // of the two places is how the human baseline record schema came to be
  // documented but never applied.
  if (createEvaluationContractValidator(repositoryRoot)(
    manifest,
    "held-out-manifest.schema.json",
  ).length > 0) {
    contractFailure("HELD_OUT_MANIFEST", "Held-out manifest violates its contract.");
  }
  if (manifest.payload_root_environment !== PAYLOAD_ROOT_ENVIRONMENT) {
    contractFailure("HELD_OUT_MANIFEST", "Held-out manifest names an unexpected payload root.");
  }
  const cases = manifest.cases;
  if (!Array.isArray(cases) || cases.length > MAX_CASES) {
    contractFailure("HELD_OUT_MANIFEST", "Held-out case list is missing or unbounded.");
  }

  const caseIds = new Set(cases.map((entry) => entry?.case_id));
  if (caseIds.size !== cases.length) {
    contractFailure("HELD_OUT_MANIFEST", "Held-out case identifiers are not unique.");
  }
  if (knownFamilyIds) {
    for (const entry of cases) {
      if (!knownFamilyIds.has(entry?.family_id)) {
        contractFailure(
          "HELD_OUT_MANIFEST",
          `Held-out case references an unknown family: ${safeName(entry?.case_id)}.`,
        );
      }
    }
  }

  if (cases.length === 0) {
    return unavailable("no held-out cases are registered");
  }
  const configuredRoot = typeof payloadRoot === "string" ? payloadRoot.trim() : "";
  if (configuredRoot === "") {
    return unavailable(`${PAYLOAD_ROOT_ENVIRONMENT} is not configured`);
  }
  if (!path.isAbsolute(configuredRoot)) {
    contractFailure("HELD_OUT_ROOT", `${PAYLOAD_ROOT_ENVIRONMENT} must be absolute.`);
  }

  const resolvedRoot = path.resolve(configuredRoot);
  if (!fs.existsSync(resolvedRoot) || !fs.lstatSync(resolvedRoot).isDirectory()) {
    contractFailure("HELD_OUT_ROOT", "Held-out payload root is missing or is not a directory.");
  }

  const resolved = [];
  for (const entry of cases) {
    const payloadPath = path.resolve(resolvedRoot, entry.relative_path);
    if (!isWithin(payloadPath, resolvedRoot)) {
      contractFailure("HELD_OUT_PATH", `Held-out case escapes its payload root: ${safeName(entry.case_id)}.`);
    }
    if (hasLinkedAncestor(resolvedRoot, payloadPath)) {
      contractFailure("HELD_OUT_PATH", `Held-out case is reached through a link: ${safeName(entry.case_id)}.`);
    }
    if (!fs.existsSync(payloadPath) || !fs.lstatSync(payloadPath).isFile()) {
      contractFailure("HELD_OUT_PAYLOAD", `Held-out case payload is missing: ${safeName(entry.case_id)}.`);
    }
    const size = fs.lstatSync(payloadPath).size;
    if (size > MAX_PAYLOAD_BYTES || size !== entry.byte_size) {
      contractFailure("HELD_OUT_PAYLOAD", `Held-out case size drifted: ${safeName(entry.case_id)}.`);
    }
    const digest = `sha256:${createHash("sha256").update(fs.readFileSync(payloadPath)).digest("hex")}`;
    if (digest !== entry.content_digest) {
      contractFailure("HELD_OUT_PAYLOAD", `Held-out case digest drifted: ${safeName(entry.case_id)}.`);
    }
    resolved.push({
      caseId: entry.case_id,
      familyId: entry.family_id,
      contaminationTag: entry.contamination_tag,
      contentDigest: entry.content_digest,
      byteSize: entry.byte_size,
      payloadPath,
    });
  }

  const scorable = resolved.filter((entry) => entry.contaminationTag !== "quarantined");
  if (scorable.length === 0) {
    return unavailable("every registered held-out case is quarantined");
  }
  return { status: "RESOLVED", reason: null, cases: scorable, quarantined: resolved.length - scorable.length };
}
