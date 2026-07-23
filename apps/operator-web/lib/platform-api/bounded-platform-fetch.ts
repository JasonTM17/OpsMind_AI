import { randomUUID } from "node:crypto";

import {
  platformEndpoint,
  type PlatformClientConfiguration,
} from "./platform-client-configuration";

const MAXIMUM_RESPONSE_BYTES = 524_288;
const OPERATOR_PROJECTION_MEDIA_TYPE =
  "application/vnd.opsmind.operator-projection.v1+json";
const BROWSER_SAFE_CLASSIFICATION = "operator-browser-safe-v1";
const DISPLAY_REDACTION_VERSION = "display-redaction-v1";
const CLASSIFICATION_HEADER = "x-opsmind-projection-class";
const REDACTION_VERSION_HEADER = "x-opsmind-redaction-version";
const REDACTION_COUNT_HEADER = "x-opsmind-redaction-count";
const MAXIMUM_REDACTION_COUNT = 999_999;

export interface PlatformProjectionAssurance {
  classification: typeof BROWSER_SAFE_CLASSIFICATION;
  redactionVersion: typeof DISPLAY_REDACTION_VERSION;
  redactionCount: number;
}

export type PlatformFailureKind =
  | "not-found"
  | "access-denied"
  | "dependency-unavailable"
  | "invalid-response";

export class PlatformRequestError extends Error {
  constructor(
    readonly kind: PlatformFailureKind,
    readonly correlationId: string,
  ) {
    super(`Platform request failed: ${kind}`);
    this.name = "PlatformRequestError";
  }
}

export async function fetchPlatformJson(
  configuration: PlatformClientConfiguration,
  path: string,
  accessToken: string,
  correlationId = randomUUID(),
  parentSignal?: AbortSignal,
): Promise<{
  body: unknown;
  correlationId: string;
  assurance: PlatformProjectionAssurance;
}> {
  let response: Response;
  try {
    const timeoutSignal = AbortSignal.timeout(configuration.timeoutMilliseconds);
    response = await fetch(platformEndpoint(configuration.baseUrl, path), {
      method: "GET",
      cache: "no-store",
      redirect: "error",
      signal: parentSignal === undefined
        ? timeoutSignal
        : AbortSignal.any([parentSignal, timeoutSignal]),
      headers: {
        Accept: OPERATOR_PROJECTION_MEDIA_TYPE,
        Authorization: `Bearer ${accessToken}`,
        "X-Correlation-Id": correlationId,
      },
    });
  } catch {
    throw new PlatformRequestError("dependency-unavailable", correlationId);
  }
  if (!response.ok) {
    await discardBody(response);
    if (response.status === 401 || response.status === 403) {
      throw new PlatformRequestError("access-denied", correlationId);
    }
    if (response.status === 404) {
      throw new PlatformRequestError("not-found", correlationId);
    }
    throw new PlatformRequestError("dependency-unavailable", correlationId);
  }
  const assurance = await readProjectionAssurance(response, correlationId);
  const mediaType = response.headers.get("content-type")?.split(";", 1)[0]?.trim().toLowerCase();
  if (mediaType !== OPERATOR_PROJECTION_MEDIA_TYPE) {
    await discardBody(response);
    throw new PlatformRequestError("invalid-response", correlationId);
  }
  const declaredLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(declaredLength) && declaredLength > MAXIMUM_RESPONSE_BYTES) {
    await discardBody(response);
    throw new PlatformRequestError("invalid-response", correlationId);
  }
  const bytes = await readBoundedBody(response, correlationId);
  try {
    return {
      body: JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)),
      correlationId,
      assurance,
    };
  } catch {
    throw new PlatformRequestError("invalid-response", correlationId);
  }
}

async function readProjectionAssurance(
  response: Response,
  correlationId: string,
): Promise<PlatformProjectionAssurance> {
  const classification = response.headers.get(CLASSIFICATION_HEADER);
  const redactionVersion = response.headers.get(REDACTION_VERSION_HEADER);
  const redactionCountText = response.headers.get(REDACTION_COUNT_HEADER);
  const redactionCount = redactionCountText !== null && /^(?:0|[1-9]\d{0,5})$/u.test(redactionCountText)
    ? Number(redactionCountText)
    : Number.NaN;
  if (
    classification !== BROWSER_SAFE_CLASSIFICATION ||
    redactionVersion !== DISPLAY_REDACTION_VERSION ||
    !Number.isSafeInteger(redactionCount) ||
    redactionCount > MAXIMUM_REDACTION_COUNT
  ) {
    await discardBody(response);
    throw new PlatformRequestError("invalid-response", correlationId);
  }
  return {
    classification: BROWSER_SAFE_CLASSIFICATION,
    redactionVersion: DISPLAY_REDACTION_VERSION,
    redactionCount,
  };
}

async function readBoundedBody(response: Response, correlationId: string): Promise<Uint8Array> {
  if (response.body === null) {
    throw new PlatformRequestError("invalid-response", correlationId);
  }
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAXIMUM_RESPONSE_BYTES) {
        await reader.cancel();
        throw new PlatformRequestError("invalid-response", correlationId);
      }
      chunks.push(value);
    }
  } catch (error) {
    if (error instanceof PlatformRequestError) throw error;
    throw new PlatformRequestError("dependency-unavailable", correlationId);
  }
  const result = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return result;
}

async function discardBody(response: Response): Promise<void> {
  try {
    await response.body?.cancel();
  } catch {
    // The response is already classified; body cleanup must not change it.
  }
}
