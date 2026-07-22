import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

const root = process.cwd();
const errors = [];
const read = (relativePath) => {
  const file = path.join(root, relativePath);
  if (!fs.existsSync(file)) {
    errors.push(`missing file: ${relativePath}`);
    return "";
  }
  return fs.readFileSync(file, "utf8");
};
const schemaPaths = [
  "packages/contracts/json-schema/ai-runtime/v1/analysis-request.schema.json",
  "packages/contracts/json-schema/ai-runtime/v1/analysis-response.schema.json",
  "packages/contracts/json-schema/ai-runtime/v1/problem-details.schema.json",
];
const schemas = schemaPaths.map((relativePath) => {
  const text = read(relativePath);
  try { return JSON.parse(text); } catch { errors.push(`invalid JSON schema: ${relativePath}`); return {}; }
});
const requestText = schemas[0];
const responseText = schemas[1];
if (requestText.additionalProperties !== false || responseText.additionalProperties !== false) {
  errors.push("analysis schemas must reject unknown fields");
}
if (requestText.properties?.schema_version?.const !== "analysis-v1" || responseText.properties?.schema_version?.const !== "analysis-v1") {
  errors.push("analysis schemas must pin schema_version=analysis-v1");
}
if (requestText["x-max-request-body-bytes"] !== 1048576) {
  errors.push("analysis request schema must publish the 1 MiB ingress contract");
}
const settings = read("services/ai-runtime/src/opsmind_ai_runtime/config/settings.py");
const main = read("services/ai-runtime/src/opsmind_ai_runtime/main.py");
const adapter = read("services/ai-runtime/src/opsmind_ai_runtime/providers/deepseek/adapter.py");
const service = read("services/ai-runtime/src/opsmind_ai_runtime/application/analysis_service.py");
const admissionGate = read("services/ai-runtime/src/opsmind_ai_runtime/application/admission_gate.py");
const delegation = read("services/ai-runtime/src/opsmind_ai_runtime/application/delegated_capability.py");
const asymmetricVerifier = read("services/ai-runtime/src/opsmind_ai_runtime/application/rsa_jwks_capability.py");
const asymmetricParser = read("services/ai-runtime/src/opsmind_ai_runtime/application/rsa_jwks_parser.py");
const providerClient = read("services/ai-runtime/src/opsmind_ai_runtime/providers/deepseek/client.py");
const egressPolicy = read("services/ai-runtime/src/opsmind_ai_runtime/application/egress_policy.py");
const providerProbe = read("services/ai-runtime/src/opsmind_ai_runtime/providers/deepseek/capability_probe.py");
const tenantEgressPolicy = read("services/ai-runtime/src/opsmind_ai_runtime/application/tenant_egress_policy.py");
const asymmetricIssuer = read("services/platform-api/src/main/java/ai/opsmind/platform/analysis/RsaAnalysisCapabilityTokenIssuer.java");
const asymmetricConfiguration = read("services/platform-api/src/main/java/ai/opsmind/platform/analysis/AnalysisCapabilityConfiguration.java");
const requestCanonicalizer = read("services/platform-api/src/main/java/ai/opsmind/platform/analysis/AnalysisRequestCanonicalizer.java");
const runtimeClient = read("services/platform-api/src/main/java/ai/opsmind/platform/analysis/HttpAnalysisRuntimeClient.java");
const boundedResponse = read("services/platform-api/src/main/java/ai/opsmind/platform/analysis/BoundedResponseBodySubscriber.java");
const deadlineExchange = read("services/platform-api/src/main/java/ai/opsmind/platform/analysis/DeadlineBoundedAnalysisHttpExchange.java");
const clientConfiguration = read("services/platform-api/src/main/java/ai/opsmind/platform/analysis/AnalysisRuntimeClientConfiguration.java");
const analysisController = read("services/platform-api/src/main/java/ai/opsmind/platform/analysis/IncidentAnalysisController.java");
const analysisService = read("services/platform-api/src/main/java/ai/opsmind/platform/analysis/IncidentAnalysisService.java");
const evidenceResolver = read("services/platform-api/src/main/java/ai/opsmind/platform/analysis/AnalysisEvidenceResolver.java");
const authoritativeEvidenceResolver = read("services/platform-api/src/main/java/ai/opsmind/platform/analysis/AuthoritativeAnalysisEvidenceResolver.java");
const incidentRedactor = read("services/platform-api/src/main/java/ai/opsmind/platform/analysis/IncidentEvidenceRedactor.java");
const platformPom = read("services/platform-api/pom.xml");
const publicAnalysisRequest = read("services/platform-api/src/main/java/ai/opsmind/platform/analysis/StartIncidentAnalysisRequest.java");
const javaResponseContractTest = read("services/platform-api/src/test/java/ai/opsmind/platform/analysis/AnalysisRuntimeResponseContractTest.java");
const pythonSchemaContractTest = read("services/ai-runtime/tests/contract/test_canonical_json_schema_alignment.py");
const analysisAuthorizer = read("services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentAnalysisAuthorizer.java");
const conformanceRunner = read("scripts/validation/run-phase-05-capability-conformance.ps1");
const goldenDigest = read("packages/contracts/fixtures/deepseek/analysis-request-v1.digest").trim();
const bodyLimit = read("services/ai-runtime/src/opsmind_ai_runtime/api/body_limit.py");
const runtimeState = read("services/ai-runtime/src/opsmind_ai_runtime/application/runtime_state.py");
const postgresState = read("services/ai-runtime/src/opsmind_ai_runtime/adapters/persistence/postgres_runtime_state.py");
const probeAudit = read("services/ai-runtime/src/opsmind_ai_runtime/adapters/persistence/postgres_capability_probe_audit.py");
const postgresPrepare = read("services/ai-runtime/src/opsmind_ai_runtime/adapters/persistence/postgres_runtime_state_prepare.py");
const postgresFinish = read("services/ai-runtime/src/opsmind_ai_runtime/adapters/persistence/postgres_runtime_state_finish.py");
const stateMigration = read("services/platform-api/src/main/resources/db/migration/V004__ai_runtime_durable_state.sql");
const probeAuditMigration = read("services/platform-api/src/main/resources/db/migration/V005__ai_runtime_capability_probe_audit.sql");
const stateRunner = read("scripts/validation/run-phase-05-local-postgres-state.ps1");
const manifest = read("services/ai-runtime/pyproject.toml");
if (!settings.includes('"deepseek-v4-flash"') || !settings.includes('provider == "deepseek"')) {
  errors.push("DeepSeek default/allowlist guard missing");
}
if (!settings.includes('provider_host in self.allowed_provider_hosts') || !settings.includes('and self.egress_enabled') ||
    !settings.includes('and bool(self.api_key)') || !settings.includes('and bool(self.allowed_data_classes)') ||
    !settings.includes('and _REGION_PATTERN.fullmatch(self.provider_region)') ||
    !settings.includes('and bool(self.egress_policy_file)')) {
  errors.push("provider readiness must require exact host, policy flag, key, region, policy file, and data class");
}
if (!settings.includes('self.input_cost_per_million_usd > 0') || !settings.includes('self.output_cost_per_million_usd > 0')) {
  errors.push("live provider readiness must require positive pricing");
}
if (!settings.includes("self.provider_ready and self.state_ready and self.delegation_ready") || !main.includes("settings.runtime_ready")) {
  errors.push("live provider readiness must require shared state and asymmetric delegation");
}
if (!main.includes('runtime_status = "ok" if settings.provider == "disabled" else "degraded"') || !main.includes('health_status="degraded"')) {
  errors.push("missing degraded health state");
}
if (!adapter.includes("reasoning_content") || !adapter.includes('ConfigDict(extra="forbid"')) {
  errors.push("adapter must discard reasoning content and reject schema drift");
}
if (!service.includes("_validate_response_scope") || !service.includes("max_completion_tokens=prepared.allowance.max_completion_tokens")) {
  errors.push("analysis service must bind citations and provider cap to atomic allowance");
}
if (!delegation.includes("analysis_request_digest") || !delegation.includes("max_lifetime")) {
  errors.push("delegated capability must bind exact request and maximum lifetime");
}
if (!asymmetricVerifier.includes('algorithms=["RS256"]') || !asymmetricParser.includes("_reject_duplicate_keys") ||
    !asymmetricVerifier.includes("decode_jwt_object") ||
    !main.includes("RsaJwksCapabilityVerifier.from_file") || main.includes("HmacCapabilityVerifier")) {
  errors.push("AI runtime must verify strict local-JWKS RS256 capabilities without HMAC fallback");
}
if (!asymmetricIssuer.includes('header.put("alg", "RS256")') || !asymmetricIssuer.includes('claims.put("request_digest"') ||
    !asymmetricConfiguration.includes("Pkcs8RsaPrivateKeyLoader")) {
  errors.push("Platform API asymmetric capability issuer or secret-mounted key loader missing");
}
if (!requestCanonicalizer.includes("TreeMap") || !requestCanonicalizer.includes("RequestDigest.sha256(body)") ||
    !requestCanonicalizer.includes('payload.put("deadline_at", deadline.toString())')) {
  errors.push("Platform API canonical request bytes/digest boundary missing");
}
if (!analysisController.includes('/{incidentId}/analysis') || !analysisAuthorizer.includes("IncidentAccessMode.ANALYZE") ||
    !analysisAuthorizer.includes("incidentRepository.find")) {
  errors.push("nested incident analysis authorization route missing");
}
if (!boundedResponse.includes("maximumBytes - buffer.size()") || !deadlineExchange.includes("timeout.toNanos()") ||
    !deadlineExchange.includes("subscriber.abort()") || !clientConfiguration.includes("Redirect.NEVER")) {
  errors.push("deadline-bounded, size-bounded, no-redirect Platform-to-Runtime client missing");
}
if (!analysisService.includes("evidenceResolver.resolve") ||
    !evidenceResolver.includes("No caller-declared evidence may be trusted") ||
    !authoritativeEvidenceResolver.includes("implements AnalysisEvidenceResolver") ||
    !authoritativeEvidenceResolver.includes('List.of("redacted_incident_summary")') ||
    !analysisAuthorizer.includes("requireEvidence") ||
    !analysisAuthorizer.includes("AuthorizedIncidentAnalysisEvidence.from(incident, actor.id())") ||
    publicAnalysisRequest.includes("String prompt") || publicAnalysisRequest.includes("contextRefs")) {
  errors.push("caller-independent authoritative analysis evidence boundary missing");
}
if (!tenantEgressPolicy.includes("class TenantEgressPolicy") ||
    !tenantEgressPolicy.includes("key = (rule.tenant_id, rule.purpose, rule.provider, rule.region)") ||
    !tenantEgressPolicy.includes("_MAX_POLICY_BYTES") ||
    !service.includes("self._egress_policy")) {
  errors.push("strict tenant/purpose/provider/region egress policy missing");
}
if (tenantEgressPolicy.includes("REDACTED_INCIDENT_SUMMARY")) {
  errors.push("unapproved incident-summary class must not enter external egress policy");
}
if (!providerProbe.includes("class DeepSeekCapabilityProbe") ||
    !providerProbe.includes("StartupGatedAnalysisService") ||
    !providerProbe.includes('thinking=True') ||
    !main.includes("capability_probe.verify()") ||
    !main.includes("StartupGatedAnalysisService")) {
  errors.push("startup provider/model capability probe and traffic gate missing");
}
if (settings.includes('"deepseek-v4-pro"')) {
  errors.push("unproven DeepSeek Pro model must not be in the live allowlist");
}
if (!providerClient.includes("max_retries != 0") || !settings.includes('"AI_PROVIDER_MAX_RETRIES", 0, 0, 0')) {
  errors.push("non-idempotent provider POST retries must remain disabled");
}
if (!providerClient.includes("trust_env=False")) {
  errors.push("provider transport must not inherit ambient proxy or CA environment settings");
}
if (!egressPolicy.includes("eyJ") || !egressPolicy.toLowerCase().includes("authorization") || !incidentRedactor.includes("eyJ")) {
  errors.push("egress redaction must cover complete bearer/JWT credential values in both runtimes");
}
for (const structuredSecretKey of [
  "api[-_]?credential",
  "bearer[-_]?token",
  "authorization[-_]?header",
  "set[-_]?cookie",
]) {
  if (!egressPolicy.includes(structuredSecretKey) || !incidentRedactor.includes(structuredSecretKey)) {
    errors.push(`cross-runtime structured secret redaction missing: ${structuredSecretKey}`);
  }
}
if (!main.includes('runtime_app.get("/ready"') || !main.includes("response.status_code = 200 if status_value == \"ok\" else 503") ||
    !main.includes("_readiness_monitor")) {
  errors.push("liveness/readiness split and dependency re-probing are required");
}
if (responseText.properties?.model_id?.maxLength !== 256 || responseText.properties?.prompt_version?.maxLength !== 256) {
  errors.push("analysis response schema must enforce bounded model and prompt identifiers");
}
if (!javaResponseContractTest.includes("rejectsUsageThatDoesNotEqualItsComponents") ||
    !javaResponseContractTest.includes("rejectsCompleteHypothesisCitationAbsentFromTopLevelCitations") ||
    !pythonSchemaContractTest.includes("test_runtime_contract_executes_published_usage_invariant") ||
    !pythonSchemaContractTest.includes("test_runtime_contract_executes_published_citation_subset_invariant")) {
  errors.push("non-expressible response semantics must have executable Python and Java rejection tests");
}
if (!platformPom.includes("<postgresql.version>42.7.13</postgresql.version>")) {
  errors.push("platform API must pin pgJDBC outside the active channel-binding downgrade advisory range");
}
if (!service.includes("runtime.overloaded") || !admissionGate.includes("try_acquire")) {
  errors.push("fail-fast bounded runtime admission gate missing");
}
if (!conformanceRunner.includes("CrossLanguageAnalysisCapabilityConformanceTest") ||
    !/^sha256:[0-9a-f]{64}$/.test(goldenDigest)) {
  errors.push("cross-language capability conformance runner or golden digest missing");
}
if (!bodyLimit.includes("self._max_chunks") || !bodyLimit.includes("asyncio.timeout")) {
  errors.push("ingress must bound bytes, chunks, and receive time");
}
if (!manifest.includes('"httpx2==2.7.0"')) errors.push("pinned httpx2 dependency missing");
if (!manifest.includes('"psycopg[binary]==3.3.4"') || !manifest.includes('"psycopg-pool==3.3.1"')) {
  errors.push("pinned PostgreSQL runtime-state dependencies missing");
}
if (!manifest.includes('"PyJWT[crypto]==2.13.0"')) errors.push("pinned PyJWT crypto dependency missing");
if (!runtimeState.includes("replay_response") || !postgresState.includes("PostgresRuntimeStateStore")) {
  errors.push("durable runtime-state port/adapter missing");
}
if (!postgresPrepare.includes("FOR UPDATE") || !postgresPrepare.includes("runtime.lease_expired") ||
    !postgresFinish.includes("active_reserved_tokens")) {
  errors.push("shared budget reservation, lease recovery, or conservative failure accounting missing");
}
if (!stateMigration.includes("opsmind_ai_runtime") || !stateMigration.includes("FORCE ROW LEVEL SECURITY") ||
    !stateMigration.includes("capability_nonces") || !stateMigration.includes("analysis_run_budgets") ||
    !stateMigration.includes("analysis_invocations")) {
  errors.push("tenant-scoped durable AI runtime schema missing");
}
if (!providerProbe.includes("ProbeUsage") || !providerProbe.includes("record_started") ||
    !probeAudit.includes("provider_capability_probe_events") ||
    !probeAudit.includes("pg_advisory_xact_lock") ||
    !probeAudit.includes("transaction_timestamp() - interval '1 hour'") ||
    !probeAuditMigration.includes("provider_capability_probe_events_started_quota_idx") ||
    !probeAuditMigration.includes("provider_capability_probe_cancelled") ||
    !probeAuditMigration.includes("REVOKE UPDATE, DELETE, TRUNCATE")) {
  errors.push("synthetic provider probes must have append-only durable lifecycle and usage audit");
}
if (!main.includes("startup_jitter_seconds=30") ||
    !main.includes("randbelow(startup_jitter_seconds + 1)")) {
  errors.push("provider capability probes must jitter replica startup calls");
}
if (!stateRunner.includes("OPSMIND_PHASE5_DB_INTEGRATION") || !stateRunner.includes("--tmpfs")) {
  errors.push("disposable PostgreSQL state integration runner missing");
}

let revision = "UNBORN";
try { revision = execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim(); } catch {}
let dirty = "UNKNOWN";
try { dirty = execFileSync("git", ["status", "--porcelain"], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim() ? "YES" : "NO"; } catch {}
console.log("OpsMind Phase 5 AI runtime validation");
console.log("EvidenceSchemaVersion=phase5-ai-runtime-static-v1");
console.log("ReleaseEvidence=NO");
console.log(`CodeRevision=${revision}`);
console.log(`WorkspaceDirty=${dirty}`);
console.log(`SchemasParsed=${schemas.length}`);
console.log("ProviderContract=provider-neutral");
console.log("EgressDefault=disabled");
console.log(`CheckpointErrors=${errors.length}`);
if (errors.length > 0) {
  for (const error of errors) console.log(`Error=${error}`);
}
const checkpointResult = errors.length === 0 ? "PASS" : "BLOCK";
const phaseExitBlockers = [];
const blockerRegister = read("docs/blockers.md");
if (/\| B-004 \|[^\n]*\| Active \|/.test(blockerRegister)) {
  phaseExitBlockers.push("B-004 provider region/terms/retention verification remains active");
}
const liveEvidencePath = path.join(
  root,
  "artifacts/verification/phase-05/live-deepseek-synthetic-smoke.txt",
);
const liveEvidence = fs.existsSync(liveEvidencePath)
  ? fs.readFileSync(liveEvidencePath, "utf8")
  : "";
const liveEvidencePassed =
  liveEvidence.includes("EvidenceSchemaVersion=phase5-live-deepseek-v1") &&
  liveEvidence.includes("SyntheticInput=YES") &&
  liveEvidence.includes("IncidentData=NO") &&
  liveEvidence.includes("CredentialPersisted=NO") &&
  liveEvidence.includes("Result=PASS");
if (!liveEvidencePassed) {
  phaseExitBlockers.push(
    "rotated externally injected staging credential and passing synthetic smoke evidence are absent",
  );
}
console.log(`CheckpointResult=${checkpointResult}`);
console.log("AuthoritativeEvidenceResolver=" + (authoritativeEvidenceResolver ? "PRESENT" : "MISSING"));
console.log("StartupProviderCapabilityProbe=" + (providerProbe ? "PRESENT" : "MISSING"));
console.log(`LiveSyntheticSmoke=${liveEvidencePassed ? "PASS" : "NOT_RUN"}`);
console.log(`PhaseExitBlockers=${phaseExitBlockers.length}`);
for (const blocker of phaseExitBlockers) console.log(`PhaseExitBlocker=${blocker}`);
const phaseExitResult = checkpointResult === "PASS" && phaseExitBlockers.length === 0
  ? "PASS"
  : "BLOCK";
console.log(`PhaseExitGate=${phaseExitResult}`);
console.log(`Result=${phaseExitResult}`);
if (phaseExitResult !== "PASS") process.exitCode = 1;
