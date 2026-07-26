import { rejectDuplicateJsonKeys } from "../../scripts/validation/phase-04-incident-contracts/duplicate-json-key-detector.mjs";

const MAX_EXPORT_BYTES = 4 * 1024 * 1024;
const PROHIBITED_KEYS = new Set([
  "api_key",
  "authorization",
  "bearer_token",
  "canonical_content",
  "capability",
  "capability_token",
  "chain_of_thought",
  "credential",
  "credentials",
  "hidden_reasoning",
  "password",
  "private_key",
  "prompt",
  "provider_reasoning",
  "raw_body",
  "raw_content",
  "raw_prompt",
  "reasoning",
  "response_json",
  "secret",
]);
const UNSAFE_VALUE_PATTERNS = [
  /-----BEGIN [A-Z ]*PRIVATE KEY-----/u,
  /\bBearer\s+[A-Za-z0-9._~+/-]{8,}=*\b/iu,
  /\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/u,
  /\b(?:gh[oprsu]_|sk-|xox[baprs]-)[A-Za-z0-9_-]{8,}\b/iu,
  /\b(?:api[_ -]?key|access[_ -]?token|password|private[_ -]?key|secret)\s*[:=]\s*\S+/iu,
  /(?:<think>|chain[- ]of[- ]thought|hidden reasoning|internal reasoning)/iu,
];

// RFC 9562 defines versions 1 through 8 and pins the variant bits to 10xx, so
// the version nibble accepts 1-8 while the variant nibble stays restricted.
// Platform evidence and execution identities are version 8: they are derived
// from a domain-separated SHA-256 of the organization, run, and intent so the
// same logical tool intent always yields the same identity.
export const RFC_9562_UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;

const SAFE_IDENTIFIER = /^[A-Za-z0-9._-]{1,128}$/u;

/**
 * Render an untrusted name for a failure message.
 *
 * Contract failures are written into redacted evidence transcripts, and those
 * transcripts are read line by line for status markers. A name carrying a
 * newline can therefore forge a line that no run produced, so anything outside
 * a printable single-line shape is replaced rather than echoed.
 */
export function safeIdentifier(value) {
  return typeof value === "string" && SAFE_IDENTIFIER.test(value) ? value : "[unsafe name]";
}

export class EvaluationContractError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "EvaluationContractError";
    this.code = code;
  }
}

export function contractFailure(code, message) {
  throw new EvaluationContractError(code, message);
}

export function exactObject(value, required, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    contractFailure("INVALID_SHAPE", `${label} must be an object.`);
  }
  const actual = Object.keys(value).sort();
  const expected = [...required].sort();
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    contractFailure("UNEXPECTED_KEYS", `${label} keys do not match the contract.`);
  }
  return value;
}

export function boundedString(value, maximum, label, minimum = 1) {
  if (typeof value !== "string"
    || value.length < minimum
    || value.length > maximum) {
    contractFailure("INVALID_VALUE", `${label} is not a bounded string.`);
  }
  return value;
}

export function boundedArray(value, maximum, label) {
  if (!Array.isArray(value) || value.length > maximum) {
    contractFailure("ROW_LIMIT", `${label} exceeds its row bound.`);
  }
  return value;
}

export function boundedInteger(value, maximum, label, minimum = 0) {
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    contractFailure("INVALID_VALUE", `${label} is not a bounded integer.`);
  }
  return value;
}

export function scanSafeValues(value, path = "$") {
  if (typeof value === "string") {
    if (UNSAFE_VALUE_PATTERNS.some((pattern) => pattern.test(value))) {
      contractFailure("UNSAFE_VALUE", `Unsafe string value at ${path}.`);
    }
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) => scanSafeValues(item, `${path}[${index}]`));
    return;
  }
  if (!value || typeof value !== "object") return;
  for (const [key, child] of Object.entries(value)) {
    // The key comes from the untrusted document, so it is rendered rather than
    // echoed. Without this a crafted key reaches the transcript verbatim.
    const rendered = safeIdentifier(key);
    if (PROHIBITED_KEYS.has(key.toLowerCase())) {
      contractFailure("PROHIBITED_KEY", `Prohibited key at ${path}.${rendered}.`);
    }
    scanSafeValues(child, `${path}.${rendered}`);
  }
}

export function parseUntrustedJsonExport(rawBytes) {
  if (!(typeof rawBytes === "string" || Buffer.isBuffer(rawBytes))) {
    contractFailure("INVALID_BYTES", "Evaluation export must be UTF-8 bytes.");
  }
  const bytes = Buffer.isBuffer(rawBytes) ? rawBytes : Buffer.from(rawBytes, "utf8");
  if (bytes.length < 2 || bytes.length > MAX_EXPORT_BYTES) {
    contractFailure("BYTE_LIMIT", "Evaluation export byte bound is invalid.");
  }
  let source;
  try {
    source = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    contractFailure("INVALID_BYTES", "Evaluation export contains malformed UTF-8.");
  }
  rejectDuplicateJsonKeys(source);
  const document = JSON.parse(source);
  scanSafeValues(document);
  return { bytes, document };
}
