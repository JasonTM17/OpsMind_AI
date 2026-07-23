export interface OperatorSessionCredential {
  accessToken: string;
}

const MAXIMUM_TOKEN_LENGTH = 8_192;

/**
 * Phase 7 has no production BFF session implementation. The only enabled
 * adapter is isolated to the non-production browser contract harness.
 */
export function getOperatorSessionCredential(): OperatorSessionCredential | null {
  const mode = process.env.OPSMIND_OPERATOR_AUTH_MODE ?? "disabled";
  if (mode === "disabled") return null;
  if (mode !== "test" || process.env.NODE_ENV === "production") {
    throw new Error("The configured operator session mode is not available.");
  }
  const accessToken = process.env.OPSMIND_OPERATOR_TEST_BEARER;
  if (
    accessToken === undefined ||
    accessToken.length < 32 ||
    accessToken.length > MAXIMUM_TOKEN_LENGTH ||
    /[\r\n]/u.test(accessToken)
  ) {
    throw new Error("The test operator session credential is invalid.");
  }
  return { accessToken };
}
