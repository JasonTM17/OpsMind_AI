const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
const SHA256_PATTERN = /^sha256:[0-9a-f]{64}$/u;
const ISO_DATE_TIME_PATTERN =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/u;

export class ContractValidationError extends Error {
  constructor(path: string, reason: string) {
    super(`${path}: ${reason}`);
    this.name = "ContractValidationError";
  }
}

export function record(
  value: unknown,
  path: string,
  allowedKeys: readonly string[],
  requiredKeys: readonly string[],
): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ContractValidationError(path, "must be an object");
  }
  const result = value as Record<string, unknown>;
  const allowed = new Set(allowedKeys);
  for (const key of Object.keys(result)) {
    if (!allowed.has(key)) {
      throw new ContractValidationError(`${path}.${key}`, "is not allowed");
    }
  }
  for (const key of requiredKeys) {
    if (!(key in result)) {
      throw new ContractValidationError(`${path}.${key}`, "is required");
    }
  }
  return result;
}

export function text(
  value: unknown,
  path: string,
  minimumLength: number,
  maximumLength: number,
): string {
  if (typeof value !== "string") {
    throw new ContractValidationError(path, "is not bounded non-empty text");
  }
  const length = Array.from(value).length;
  if (
    length < minimumLength ||
    length > maximumLength ||
    (minimumLength > 0 && !/\S/u.test(value))
  ) {
    throw new ContractValidationError(path, "is not bounded non-empty text");
  }
  return value;
}

export function nullableText(
  value: unknown,
  path: string,
  maximumLength: number,
): string | null {
  return value === null ? null : text(value, path, 1, maximumLength);
}

export function uuid(value: unknown, path: string): string {
  const result = text(value, path, 36, 36);
  if (!UUID_PATTERN.test(result)) {
    throw new ContractValidationError(path, "must be a UUID");
  }
  return result;
}

export function digest(value: unknown, path: string): string {
  const result = text(value, path, 71, 71);
  if (!SHA256_PATTERN.test(result)) {
    throw new ContractValidationError(path, "must be a SHA-256 digest");
  }
  return result;
}

export function timestamp(value: unknown, path: string): string {
  const result = text(value, path, 20, 40);
  if (!ISO_DATE_TIME_PATTERN.test(result) || !Number.isFinite(Date.parse(result))) {
    throw new ContractValidationError(path, "must be an ISO date-time");
  }
  return result;
}

export function integer(
  value: unknown,
  path: string,
  minimum: number,
  maximum: number,
): number {
  if (!Number.isInteger(value) || (value as number) < minimum || (value as number) > maximum) {
    throw new ContractValidationError(path, `must be an integer from ${minimum} to ${maximum}`);
  }
  return value as number;
}

export function decimal(
  value: unknown,
  path: string,
  minimum: number,
  maximum: number,
): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value < minimum || value > maximum) {
    throw new ContractValidationError(path, `must be a number from ${minimum} to ${maximum}`);
  }
  return value;
}

export function enumeration<const T extends readonly string[]>(
  value: unknown,
  path: string,
  values: T,
): T[number] {
  if (typeof value !== "string" || !values.includes(value)) {
    throw new ContractValidationError(path, `must be one of ${values.join(", ")}`);
  }
  return value as T[number];
}

export function array(
  value: unknown,
  path: string,
  maximumItems: number,
): unknown[] {
  if (!Array.isArray(value) || value.length > maximumItems) {
    throw new ContractValidationError(path, `must contain at most ${maximumItems} items`);
  }
  return value;
}
