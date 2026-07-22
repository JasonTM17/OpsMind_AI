import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  prepareValidationEvidence,
  publishValidationEvidence,
} from "./safe-validation-evidence.mjs";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "../..");
const errors = [];

const requiredFiles = [
  "packages/contracts/openapi/opsmind-v1.yaml",
  "packages/contracts/json-schema/common/problem-details.schema.json",
  "packages/contracts/json-schema/auth/principal-claims.schema.json",
  "packages/contracts/json-schema/auth/tenant-scope.schema.json",
  "packages/contracts/json-schema/auth/delegated-capability.schema.json",
  "packages/contracts/fixtures/auth/principal-claims-valid.json",
  "packages/contracts/fixtures/auth/delegated-capability-valid.json",
  "services/platform-api/src/main/resources/db/migration/V001__identity_tenant_foundation.sql",
  "services/platform-api/src/main/resources/db/migration/V002__outbox_dispatcher_workload.sql",
  "services/platform-api/src/main/resources/db/bootstrap/001-create-runtime-role.sh",
  "services/platform-api/src/main/resources/application-persistence.yaml",
  "services/platform-api/src/main/java/ai/opsmind/platform/identity/SecurityConfiguration.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/identity/OidcAccessTokenValidator.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/identity/OidcOutboundRequestRateLimiter.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/identity/PlatformUserStatusVerifier.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/identity/ActivePlatformUserFilter.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/identity/ActivePlatformUserFilterTest.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/identity/PlatformSecurityPropertiesTest.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/identity/OidcOutboundRequestRateLimiterTest.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/identity/PlatformUserStatusVerifierTest.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/identity/SecurityConfigurationTest.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/identity/PlatformUserStatusVerifierIntegrationTest.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/common/api/IdempotencyKey.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/common/api/RequestDigest.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/common/api/OptimisticConcurrency.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/persistence/IdempotencyRepository.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/persistence/TransactionalIdempotencyRepository.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/tenancy/TenantContextSql.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/tenancy/TenantRlsPoolIntegrationTest.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/messaging/OutboxLease.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/messaging/OutboxLeaseRepository.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/messaging/OutboxDispatcherTenantScheduler.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/messaging/OutboxDispatcherTenantContextSql.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/messaging/TransactionalOutboxRepository.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/messaging/TransactionalOutboxAppender.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/messaging/TransactionalOutboxLeaseStore.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/messaging/TransactionalInboxRepository.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/messaging/TransactionalOutboxIntegrationTest.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/messaging/TransactionalInboxIntegrationTest.java",
  "services/platform-api/src/test/java/ai/opsmind/platform/messaging/OutboxDispatcherWorkloadIntegrationTest.java",
  "services/platform-api/src/main/java/ai/opsmind/platform/delegation/DelegatedCapabilityValidator.java",
  "scripts/validation/run-phase-03-postgres-contract.sh",
  "scripts/validation/run-phase-03-local-postgres-contract.ps1",
  "scripts/validation/run-phase-03-keycloak-conformance.ps1",
  "scripts/validation/verify-phase-03-keycloak-evidence.ps1",
  "scripts/validation/keycloak/keycloak-conformance-support.ps1",
  "scripts/validation/keycloak/keycloak-conformance-profile-files.txt",
  "scripts/validation/keycloak/opsmind-conformance-realm.json",
  "scripts/validation/keycloak/oidc_browser_flow.py",
  "scripts/validation/keycloak/run_oidc_conformance.py",
];

for (const file of requiredFiles) {
  if (!fs.existsSync(path.join(repositoryRoot, file))) errors.push(`missing required file: ${file}`);
}

const dispatcherMigrationPath =
  "services/platform-api/src/main/resources/db/migration/V002__outbox_dispatcher_workload.sql";
if (fs.existsSync(path.join(repositoryRoot, dispatcherMigrationPath))) {
  const migration = read(dispatcherMigrationPath);
  requireContracts(migration, "dispatcher migration", [
    "required outbox role opsmind_dispatcher is missing",
    "required opsmind_dispatch_resolver role is missing",
    "CREATE POLICY outbox_events_dispatch_resolution",
    "CREATE OR REPLACE FUNCTION opsmind_list_dispatch_tenants",
    "CREATE OR REPLACE FUNCTION opsmind_set_dispatcher_tenant_context",
    "session_user <> 'opsmind_dispatcher'",
    "database_principal = session_user",
    "dispatcher transaction is already bound to another tenant",
    "poisoned_at) ON outbox_events FROM opsmind_app",
    "TO opsmind_dispatcher",
  ]);
  if (/ALTER\s+ROLE\s+opsmind_dispatcher\s+BYPASSRLS/i.test(migration)) {
    errors.push("dispatcher migration must not grant BYPASSRLS");
  }
}

function read(relativePath) {
  return fs.readFileSync(path.join(repositoryRoot, relativePath), "utf8");
}

function requireContracts(contents, label, contracts) {
  for (const contract of contracts) {
    if (!contents.includes(contract)) errors.push(`${label} is missing contract: ${contract}`);
  }
}

for (const file of requiredFiles.filter((entry) => entry.endsWith(".json"))) {
  if (!fs.existsSync(path.join(repositoryRoot, file))) continue;
  try {
    const parsed = JSON.parse(read(file));
    if (typeof parsed !== "object" || parsed === null) errors.push(`JSON root must be an object: ${file}`);
  } catch (error) {
    errors.push(`invalid JSON: ${file}: ${error.message}`);
  }
}

const principalClaims = read("packages/contracts/json-schema/auth/principal-claims.schema.json");
requireContracts(principalClaims, "principal claims", [
  '"required": ["iss", "sub", "aud", "exp", "iat", "amr"]',
  '"auth_time"',
  '"maxItems": 20',
]);

const openApiPath = "packages/contracts/openapi/opsmind-v1.yaml";
if (fs.existsSync(path.join(repositoryRoot, openApiPath))) {
  requireContracts(read(openApiPath), "OpenAPI", [
    "openapi: 3.1.1",
    "/me:",
    "/organizations/{organizationId}/projects:",
    "oidcBearer:",
    "Idempotency-Key",
    "If-Match",
    "application/problem+json:",
    "../json-schema/common/problem-details.schema.json",
    "../json-schema/auth/tenant-scope.schema.json",
  ]);
}

const migrationPath = "services/platform-api/src/main/resources/db/migration/V001__identity_tenant_foundation.sql";
if (fs.existsSync(path.join(repositoryRoot, migrationPath))) {
  const migration = read(migrationPath);
  requireContracts(migration, "identity migration", [
    "CREATE TABLE organizations",
    "CREATE TABLE platform_users",
    "CREATE TABLE organization_memberships",
    "CREATE TABLE projects",
    "CREATE TABLE environments",
    "CREATE TABLE service_accounts",
    "CREATE TABLE idempotency_records",
    "CREATE TABLE outbox_events",
    "CREATE TABLE inbox_events",
    "CREATE TABLE audit_events",
    "FORCE ROW LEVEL SECURITY",
    "opsmind_current_tenant_id()",
    "set_config('opsmind.tenant_id'",
    "set_config('opsmind.actor_id'",
    "CREATE TRIGGER audit_events_no_update",
    "UNIQUE (organization_id, aggregate_type, aggregate_id, aggregate_sequence)",
    "PRIMARY KEY (organization_id, event_id, consumer)",
    "payload_bytes",
    "lease_token",
    "lease_expires_at",
    "poisoned_at",
    "outbox_dispatch_ready_idx",
    "CREATE OR REPLACE FUNCTION opsmind_enforce_outbox_sequence",
    "pg_advisory_xact_lock",
    "CREATE TRIGGER outbox_events_enforce_sequence",
    "ERRCODE = 'P3001'",
    "required non-owner runtime role opsmind_app is missing",
    "runtime role opsmind_app has unsafe attributes",
    "required opsmind_context_resolver role is missing",
    "ALTER FUNCTION public.opsmind_set_tenant_context(uuid, uuid) OWNER TO opsmind_context_resolver",
    "GRANT EXECUTE ON FUNCTION public.opsmind_current_tenant_id() TO opsmind_context_resolver",
    "GRANT EXECUTE ON FUNCTION public.opsmind_set_tenant_context(uuid, uuid) TO opsmind_app",
    "CREATE OR REPLACE FUNCTION opsmind_resolve_user",
    "GRANT EXECUTE ON FUNCTION public.opsmind_resolve_user(varchar, varchar) TO opsmind_app",
  ]);
  if (/\bBYPASSRLS\b/.test(migration)) errors.push("migration must not grant or reference BYPASSRLS");
  if (!/set_config\('opsmind\.tenant_id',[^;]+true\)/s.test(migration)) {
    errors.push("tenant context must be transaction-local");
  }
}

const application = read("services/platform-api/src/main/resources/application.yaml");
const persistence = read("services/platform-api/src/main/resources/application-persistence.yaml");
requireContracts(application, "default application profile", [
  "enabled: ${OPSMIND_DISPATCHER_ENABLED:false}",
  "mode: ${OPSMIND_SECURITY_MODE:fail-closed}",
  "required-amr: ${OIDC_REQUIRED_AMR:mfa}",
  "maximum-token-lifetime: ${OIDC_MAX_TOKEN_LIFETIME:PT5M}",
  "clock-skew: ${OIDC_CLOCK_SKEW:PT60S}",
  "jwks-refresh-minimum-interval: ${OIDC_JWKS_REFRESH_MINIMUM_INTERVAL:PT1S}",
  "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
]);
requireContracts(persistence, "persistence profile", [
  "SPRING_DATASOURCE_URL",
  "SPRING_DATASOURCE_USERNAME",
  "SPRING_DATASOURCE_PASSWORD",
  "enabled: ${OPSMIND_FLYWAY_ENABLED:true}",
  "opsmind:",
  "persistence:",
  "enabled: true",
]);

const idempotencyRepository = read(
  "services/platform-api/src/main/java/ai/opsmind/platform/persistence/TransactionalIdempotencyRepository.java",
);
requireContracts(idempotencyRepository, "idempotency repository", [
  "ON CONFLICT DO NOTHING",
  "FOR UPDATE",
  "request_digest = ?",
  "status = 'in_progress'",
]);

const compose = read("compose.yaml");
requireContracts(compose, "Compose platform persistence", [
  "platform-migrate:",
  "POSTGRES_APP_USER: ${POSTGRES_APP_USER:-opsmind_app}",
  "POSTGRES_APP_PASSWORD: ${POSTGRES_APP_PASSWORD:-}",
  "POSTGRES_DISPATCHER_USER: ${POSTGRES_DISPATCHER_USER:-opsmind_dispatcher}",
  "POSTGRES_DISPATCHER_PASSWORD: ${POSTGRES_DISPATCHER_PASSWORD:-}",
  "SPRING_PROFILES_ACTIVE: persistence",
  "SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:-opsmind}",
  "SPRING_DATASOURCE_USERNAME: ${POSTGRES_APP_USER:-opsmind_app}",
  "SPRING_DATASOURCE_PASSWORD: ${POSTGRES_APP_PASSWORD:-}",
  "OPSMIND_FLYWAY_ENABLED: \"false\"",
  "service_completed_successfully",
  "OPSMIND_SECURITY_MODE: ${OPSMIND_SECURITY_MODE:-fail-closed}",
  "OIDC_REQUIRED_AMR: ${OIDC_REQUIRED_AMR:-mfa}",
  "OIDC_MAX_TOKEN_LIFETIME: ${OIDC_MAX_TOKEN_LIFETIME:-PT5M}",
  "OIDC_JWKS_REFRESH_MINIMUM_INTERVAL: ${OIDC_JWKS_REFRESH_MINIMUM_INTERVAL:-PT1S}",
]);
const bootstrap = read("services/platform-api/src/main/resources/db/bootstrap/001-create-runtime-role.sh");
requireContracts(bootstrap, "runtime role bootstrap", [
    "POSTGRES_APP_PASSWORD",
    "NOSUPERUSER",
    "NOBYPASSRLS",
    "opsmind_context_resolver",
    "opsmind_dispatcher",
    "opsmind_dispatch_resolver",
    "\\password opsmind_app",
    "\\password opsmind_dispatcher",
]);
const postgresContract = read("scripts/validation/run-phase-03-postgres-contract.sh");
requireContracts(postgresContract, "PostgreSQL contract runner", [
    "OPSMIND_EPHEMERAL_DB",
    "ContextResolverRole=PASS",
    "AlternatingTenantSession=PASS",
    "OutboxSequenceContiguity=PASS",
    "IdempotencyIsolation=PASS",
    "AuditTrigger=PASS",
    "DispatcherRoleSeparation=PASS",
    "DispatcherTenantBinding=PASS",
    "DispatcherContextReset=PASS",
    "CrossTenantRead=PASS",
  "TransactionContextReset=PASS",
]);
const localPostgresContract = read("scripts/validation/run-phase-03-local-postgres-contract.ps1");
requireContracts(localPostgresContract, "local PostgreSQL harness", [
  "^opsmind_phase3_[0-9a-f]{12}$",
  "Refusing the ephemeral test because fixed OpsMind roles already exist.",
  "OPSMIND_PHASE3_DB_INTEGRATION",
  "TenantRlsPoolIntegrationTest",
  "TransactionalOutboxIntegrationTest",
  "TransactionalInboxIntegrationTest",
  "PlatformUserStatusVerifierIntegrationTest",
  "OutboxDispatcherWorkloadIntegrationTest",
  "PoolContractExit=",
  "ResidualObjects=",
]);
const poolContract = read(
  "services/platform-api/src/test/java/ai/opsmind/platform/tenancy/TenantRlsPoolIntegrationTest.java",
);
requireContracts(poolContract, "Hikari tenant-isolation contract", [
  "setMaximumPoolSize(1)",
  "SELECT pg_backend_pid()",
  "SET LOCAL statement_timeout = '50ms'",
  "assertNoTenantContext",
]);
const outboxRepository = [
  read("services/platform-api/src/main/java/ai/opsmind/platform/messaging/TransactionalOutboxAppender.java"),
  read("services/platform-api/src/main/java/ai/opsmind/platform/messaging/TransactionalOutboxLeaseStore.java"),
].join("\n");
requireContracts(outboxRepository, "transactional outbox repository", [
  "FOR UPDATE SKIP LOCKED",
  "payload_bytes",
  "lease_token = ?",
  "event.payload-integrity",
  '"P3001".equals(sqlException.getSQLState())',
]);
const appOutboxContract = read(
  "services/platform-api/src/main/java/ai/opsmind/platform/messaging/OutboxRepository.java",
);
if (appOutboxContract.includes("claimBatch") || appOutboxContract.includes("markPublished")) {
  errors.push("web outbox contract must remain append-only");
}
requireContracts(
  read("services/platform-api/src/main/java/ai/opsmind/platform/messaging/TransactionalOutboxLeaseStore.java"),
  "dispatcher lease repository",
  [
    'prefix = "opsmind.dispatcher"',
    "implements OutboxLeaseRepository",
  ],
);
const inboxRepository = read(
  "services/platform-api/src/main/java/ai/opsmind/platform/messaging/TransactionalInboxRepository.java",
);
requireContracts(inboxRepository, "transactional inbox repository", [
  "ON CONFLICT (organization_id, event_id, consumer) DO UPDATE",
  "status = 'processed'",
  "status = 'poisoned'",
]);
const oidcPolicy = read(
  "services/platform-api/src/main/java/ai/opsmind/platform/identity/OidcAccessTokenValidator.java",
);
requireContracts(oidcPolicy, "OIDC access-token policy", [
  "maximumTokenLifetime",
  "requiredAmr",
  "Access token audience is not accepted.",
  "Access token issued-at time is in the future.",
]);
const oidcConfiguration = read(
  "services/platform-api/src/main/java/ai/opsmind/platform/identity/SecurityConfiguration.java",
);
requireContracts(oidcConfiguration, "OIDC decoder configuration", [
  "NimbusJwtDecoder",
  "JwtValidators.createDefaultWithValidators",
  "new JwtTimestampValidator(properties.clockSkew())",
  "new JwtIssuerValidator(properties.issuerUri().toString())",
  "jwsAlgorithm(SignatureAlgorithm.RS256)",
  "new OidcOutboundRequestRateLimiter(properties.jwksRefreshMinimumInterval())",
]);
const oidcProperties = read(
  "services/platform-api/src/main/java/ai/opsmind/platform/identity/PlatformSecurityProperties.java",
);
requireContracts(oidcProperties, "OIDC bounded policy configuration", [
  "Duration.ofMinutes(5)",
  "Duration.ofSeconds(60)",
  "maximumTokenLifetime.compareTo(Duration.ofMinutes(5)) > 0",
  "clockSkew.compareTo(Duration.ofSeconds(60)) > 0",
  "jwksRefreshMinimumInterval.compareTo(Duration.ofMillis(100)) < 0",
]);
const activeUserFilter = read(
  "services/platform-api/src/main/java/ai/opsmind/platform/identity/ActivePlatformUserFilter.java",
);
requireContracts(activeUserFilter, "active platform-user filter", [
  "PlatformUserStatusVerifier",
  "identity.claims-invalid",
  "startsWith(\"/api/v1/\")",
  "request.getServletPath()",
]);

const keycloakRealmPath = "scripts/validation/keycloak/opsmind-conformance-realm.json";
if (fs.existsSync(path.join(repositoryRoot, keycloakRealmPath))) {
  const realm = JSON.parse(read(keycloakRealmPath));
  const browserClients = (realm.clients ?? []).filter(
    (client) => client.clientId === "opsmind-conformance-browser",
  );
  if (realm.realm !== "opsmind-conformance") errors.push("Keycloak reference realm name is not pinned");
  if (realm.sslRequired !== "external") errors.push("Keycloak reference realm must require external TLS");
  if (realm.accessTokenLifespan !== 300) errors.push("Keycloak access-token lifetime must be 300 seconds");
  if (realm.revokeRefreshToken !== true || realm.refreshTokenMaxReuse !== 0) {
    errors.push("Keycloak refresh-token rotation/reuse policy is not fail-closed");
  }
  if (realm.defaultSignatureAlgorithm !== "RS256") errors.push("Keycloak signing algorithm must be RS256");
  if (
    realm.otpPolicyType !== "totp" ||
    realm.otpPolicyAlgorithm !== "HmacSHA256" ||
    realm.otpPolicyCodeReusable !== false
  ) {
    errors.push("Keycloak TOTP profile must use HmacSHA256 with replay disabled");
  }
  if (browserClients.length !== 1) {
    errors.push("Keycloak reference realm must define exactly one conformance browser client");
  } else {
    const client = browserClients[0];
    if (
      client.publicClient !== true ||
      client.standardFlowEnabled !== true ||
      client.implicitFlowEnabled !== false ||
      client.directAccessGrantsEnabled !== false ||
      client.serviceAccountsEnabled !== false
    ) {
      errors.push("Keycloak browser client grant profile is unsafe");
    }
    if (client.attributes?.["pkce.code.challenge.method"] !== "S256") {
      errors.push("Keycloak browser client must require PKCE S256");
    }
    if (
      client.attributes?.["oauth2.device.authorization.grant.enabled"] !== "false" ||
      client.attributes?.["oidc.ciba.grant.enabled"] !== "false"
    ) {
      errors.push("Keycloak browser client must disable device and CIBA grants");
    }
    const mapperIds = new Set((client.protocolMappers ?? []).map((mapper) => mapper.protocolMapper));
    if (!mapperIds.has("oidc-audience-mapper") || !mapperIds.has("oidc-amr-mapper")) {
      errors.push("Keycloak browser client must emit audience and AMR claims");
    }
  }
}

const keycloakRunner = read("scripts/validation/run-phase-03-keycloak-conformance.ps1");
requireContracts(keycloakRunner, "Keycloak conformance runner", [
  "quay.io/keycloak/keycloak@sha256:1362a9d9f13ab325231ea133610cc905e12805804abc7acbef552dd613720aa6",
  "default.reference.value",
  "default.reference.maxAge",
  "OIDC_MAX_TOKEN_LIFETIME = 'PT5M'",
  "OIDC_JWKS_REFRESH_MINIMUM_INTERVAL = 'PT1S'",
  "--connect-timeout 2 --max-time 3",
  "'clean' 'package'",
  "EvidenceSchemaVersion=2",
  "ProfileDigestAlgorithm=SHA256_FILE_MANIFEST_V1",
  "ConformanceProfileSha256=",
  "PlatformArtifactDigestAlgorithm=SHA256",
  "PlatformArtifactSha256=",
  "ScenarioVersion=phase-03-keycloak-oidc-v2",
  "DatasetVersion=synthetic-identity-v1",
  "EvidenceScope=REFERENCE_CONFORMANCE_NOT_PRODUCTION",
  "TotpSameCodeReplayDenied=PASS",
  "PlatformTamperedSignatureDenied=PASS",
  "JwksRotationRefresh=PASS",
  "RefreshTokenRotationReuseDenied=PASS",
  "RefreshTokenIndependentSessions=PASS",
  "RefreshTokenPreRevocationControl=PASS",
  "RefreshTokenRevocation=PASS",
  "ExistingJwtAfterIdpDisable=PREISSUED_JWT_STILL_ACCEPTED",
  "AccessTokenLifetimeSeconds=300",
  "ConfiguredClockSkewSeconds=30",
  "MaximumResidualAcceptanceSeconds=330",
  "DisableToDenialHorizon=NOT_LIVE_MEASURED",
  "CleanupVerified=PASS",
  "RuntimeSecretsPersisted=NO",
  "FailureEvidenceSchemaVersion=1",
  "DiagnosticsSanitized=YES",
  "Remove-OpsMindValidatedTempDirectory",
]);
const keycloakSupport = read("scripts/validation/keycloak/keycloak-conformance-support.ps1");
requireContracts(keycloakSupport, "Keycloak conformance support", [
  "-CommandType Application",
  "ReadAsStringAsync",
  "resolvedParent.Equals",
  "Get-OpsMindConformanceProfilePaths",
  "Get-OpsMindSanitizedDiagnosticLines",
  "<redacted-runtime-value>",
  "<redacted-jwt>",
  "Get-ChildItem -LiteralPath $resolved -File -Recurse -Force",
]);
const keycloakProfileManifest = read(
  "scripts/validation/keycloak/keycloak-conformance-profile-files.txt",
);
requireContracts(keycloakProfileManifest, "Keycloak profile input manifest", [
  "packages/contracts/openapi/opsmind-v1.yaml",
  "services/platform-api/pom.xml",
  "services/platform-api/src/main/",
  "services/platform-api/src/test/",
]);
const keycloakEvidenceVerifier = read("scripts/validation/verify-phase-03-keycloak-evidence.ps1");
requireContracts(keycloakEvidenceVerifier, "Keycloak evidence verifier", [
  "Identity conformance evidence does not match the complete schema.",
  "HttpsDiscovery = 'PASS'",
  "AuthorizationCodePkceS256 = 'PASS'",
  "PlatformTamperedSignatureDenied = 'PASS'",
  "JwksRotationRefresh = 'PASS'",
  "RefreshTokenPreRevocationControl = 'PASS'",
  "CleanupVerified = 'PASS'",
  "IdentityEvidenceArtifactDigest=PASS",
]);
const oidcBrowserFlow = read("scripts/validation/keycloak/oidc_browser_flow.py");
requireContracts(oidcBrowserFlow, "OIDC browser-flow client", [
  "ssl.create_default_context(cafile=str(ca_cert))",
  "os.O_EXCL",
  "0o600",
  '"code_challenge_method": "S256"',
  '"code_verifier": verifier',
  "redirect_matches",
  "issuer_contains",
  "read_bounded",
  "RP-initiated logout did not reach the bound loopback redirect.",
]);
const oidcConformanceClient = read("scripts/validation/keycloak/run_oidc_conformance.py");
requireContracts(oidcConformanceClient, "OIDC conformance assertions", [
  "assert_pkce_required",
  '"grant_type": "password"',
  "require_mfa=False",
  "require_mfa=True",
  "seconds_until_next_totp",
  "assert_totp_replay_denied",
  '"otpReplayDenied": replay_denial',
  '"tokenLifetimeSeconds": payload["exp"] - payload["iat"]',
  'result["summary"]["previousRefreshReuseDenied"] = reused_refresh_error',
  '"independentRefreshSessions": True',
  '"refresh-once": refresh_once_profile',
  'print(f"Command={args.command} Result=PASS")',
]);
if (/print\s*\(\s*(?:result|tokens|valid_tokens|enrollment_tokens)\b/.test(oidcConformanceClient)) {
  errors.push("OIDC conformance client must not print token-bearing result objects");
}

const qualityWorkflow = read(".github/workflows/pr-quality.yml");
requireContracts(qualityWorkflow, "PR quality identity gate", [
  "identity-conformance:",
  "Run Keycloak OIDC conformance",
  "scripts/validation/run-phase-03-keycloak-conformance.ps1",
  "scripts/validation/verify-phase-03-keycloak-evidence.ps1",
  "phase-03-identity-conformance",
]);

const evidence = prepareValidationEvidence({
  repositoryRoot,
  configuredArtifactRoot: process.env.OPS_ARTIFACT_ROOT,
  configuredEvidencePath: process.env.OPS_PHASE_03_EVIDENCE_PATH,
  defaultRelativePath: path.join("verification", "phase-03", "trust-foundation.txt"),
});
if (evidence.error) errors.push(`evidence publication: ${evidence.error}`);

const lines = [
  "OpsMind Phase 3 trust-foundation validation",
  `TimestampUtc=${new Date().toISOString()}`,
  `FilesChecked=${requiredFiles.length}`,
  `Errors=${errors.length}`,
  ...errors.map((error) => `Error=${error.replace(/[\r\n]/g, " ")}`),
  `Result=${errors.length === 0 ? "PASS" : "BLOCK"}`,
];
const transcript = `${lines.join("\n")}\n`;
process.stdout.write(transcript);
if (evidence.evidencePath) publishValidationEvidence(evidence.evidencePath, transcript);
process.exit(errors.length === 0 ? 0 : 1);
