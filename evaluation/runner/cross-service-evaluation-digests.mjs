import { createHash } from "node:crypto";

const SHA256_PATTERN = /^sha256:[a-f0-9]{64}$/u;
const DOMAIN_PATTERN = /^opsmind\.[a-z0-9.-]+\/v[1-9][0-9]*$/u;

export function stableStringify(value) {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(",")}]`;
  if (value && typeof value === "object") {
    const entries = Object.keys(value).sort().map((key) => (
      `${JSON.stringify(key)}:${stableStringify(value[key])}`
    ));
    return `{${entries.join(",")}}`;
  }
  return JSON.stringify(value);
}

function sha256(bytes) {
  return `sha256:${createHash("sha256").update(bytes).digest("hex")}`;
}

export function rawByteDigest(bytes, domain) {
  if (!DOMAIN_PATTERN.test(domain)) throw new Error("Raw-byte digest domain is invalid.");
  return {
    digest_type: "raw-bytes",
    digest_domain: domain,
    digest: sha256(bytes),
  };
}

export function canonicalDigest(value, domain) {
  if (!DOMAIN_PATTERN.test(domain)) throw new Error("Canonical digest domain is invalid.");
  return {
    digest_type: "canonical-json",
    digest_domain: domain,
    digest: sha256(`${domain}\0${stableStringify(value)}`),
  };
}

export function digestReferenceValid(value, expectedType, expectedDomain) {
  return value
    && typeof value === "object"
    && !Array.isArray(value)
    && Object.keys(value).length === 3
    && value.digest_type === expectedType
    && value.digest_domain === expectedDomain
    && SHA256_PATTERN.test(value.digest ?? "");
}

export function sha256DigestValid(value) {
  return SHA256_PATTERN.test(value ?? "");
}
