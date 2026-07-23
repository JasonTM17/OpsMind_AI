const DEFAULT_TIMEOUT_MILLISECONDS = 3_000;
const MINIMUM_TIMEOUT_MILLISECONDS = 100;
const MAXIMUM_TIMEOUT_MILLISECONDS = 10_000;

export interface PlatformClientConfiguration {
  baseUrl: URL;
  timeoutMilliseconds: number;
}

export function loadPlatformClientConfiguration(): PlatformClientConfiguration {
  const rawUrl = process.env.OPSMIND_PLATFORM_API_URL;
  if (rawUrl === undefined) {
    throw new Error("The Platform API URL is not configured.");
  }
  const baseUrl = new URL(rawUrl);
  if (
    baseUrl.username !== "" ||
    baseUrl.password !== "" ||
    baseUrl.search !== "" ||
    baseUrl.hash !== ""
  ) {
    throw new Error("The Platform API URL contains prohibited components.");
  }
  const secure = baseUrl.protocol === "https:";
  const testLoopback = baseUrl.protocol === "http:" &&
    process.env.NODE_ENV !== "production" &&
    process.env.OPSMIND_PLATFORM_API_ALLOW_LOOPBACK_CLEARTEXT === "true" &&
    isLoopback(baseUrl.hostname);
  if (!secure && !testLoopback) {
    throw new Error("The Platform API URL must use HTTPS.");
  }
  if (baseUrl.pathname !== "/" && baseUrl.pathname.endsWith("/")) {
    baseUrl.pathname = baseUrl.pathname.slice(0, -1);
  }
  const timeoutMilliseconds = parseTimeout(
    process.env.OPSMIND_PLATFORM_API_TIMEOUT_MS,
  );
  return { baseUrl, timeoutMilliseconds };
}

export function platformEndpoint(baseUrl: URL, path: string): URL {
  if (!path.startsWith("/") || path.includes("..")) {
    throw new Error("The Platform API path is invalid.");
  }
  const prefix = baseUrl.pathname === "/" ? "" : baseUrl.pathname;
  const endpoint = new URL(baseUrl.origin);
  endpoint.pathname = `${prefix}${path}`;
  return endpoint;
}

function parseTimeout(raw: string | undefined): number {
  if (raw === undefined) return DEFAULT_TIMEOUT_MILLISECONDS;
  const parsed = Number(raw);
  if (
    !Number.isInteger(parsed) ||
    parsed < MINIMUM_TIMEOUT_MILLISECONDS ||
    parsed > MAXIMUM_TIMEOUT_MILLISECONDS
  ) {
    throw new Error("The Platform API timeout is outside policy.");
  }
  return parsed;
}

function isLoopback(hostname: string): boolean {
  return hostname === "127.0.0.1" || hostname === "::1" || hostname === "localhost";
}
