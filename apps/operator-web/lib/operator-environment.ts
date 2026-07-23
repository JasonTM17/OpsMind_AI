const ENVIRONMENT_LABELS = {
  local: "Local",
  development: "Development",
  test: "Test",
  staging: "Staging",
  production: "Production",
} as const;

export function operatorEnvironmentLabel(): string {
  const configured = process.env.OPSMIND_DEPLOYMENT_ENVIRONMENT?.trim().toLowerCase();
  const label = configured === undefined
    ? undefined
    : ENVIRONMENT_LABELS[configured as keyof typeof ENVIRONMENT_LABELS];
  if (process.env.NODE_ENV === "production" && label === undefined) {
    throw new Error("A recognized OPSMIND_DEPLOYMENT_ENVIRONMENT is required in production.");
  }
  return label ?? "Unspecified";
}
