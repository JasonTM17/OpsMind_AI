# OpsMind AI Runtime

The AI Runtime is a separate FastAPI trust boundary for bounded, provider-neutral incident analysis. It validates the application-owned contract, verifies a platform-issued delegated capability, applies the last-hop data-class/redaction policy, enforces per-run budgets, and only then calls a provider adapter.

## Safe default

`AI_PROVIDER=disabled` and `OPS_ENABLE_DEEPSEEK_EGRESS=false` are the checked-in defaults. A DeepSeek key alone never enables traffic. Until the G0.5 provider/data-processing decision and synthetic conformance gate pass, use only the offline fixture tests.

The Phase 7 cross-service gate can use `AI_PROVIDER=fixture` only with
`AI_FIXTURE_PROVIDER_ENABLED=true`, a loopback
`http://127.0.0.1:<port>/v1` target, and an explicit synthetic egress policy.
This mode speaks the same DeepSeek-compatible HTTP contract and startup probe,
but never contacts an external provider. It may carry the already-redacted
incident snapshot required by the Platform request shape; normal `deepseek`
egress does not gain that data class.

Copy the service example only into an untracked local environment and inject the key through the process environment or approved secret manager:

```powershell
$env:AI_PROVIDER = 'deepseek'
$env:DEEPSEEK_MODEL = 'deepseek-v4-flash'
$env:AI_PROVIDER_ALLOWED_HOSTS = 'api.deepseek.com'
$env:AI_INPUT_COST_USD_PER_MILLION = '<approved-positive-price>'
$env:AI_OUTPUT_COST_USD_PER_MILLION = '<approved-positive-price>'
$env:DEEPSEEK_API_KEY = '<runtime-secret>'
$env:OPSMIND_AI_CAPABILITY_JWKS_FILE = '<absolute-secret-mount-path-to-public-jwks.json>'
$env:OPSMIND_AI_CAPABILITY_ISSUER = 'https://platform.example.internal'
$env:OPSMIND_AI_CAPABILITY_AUDIENCE = 'opsmind-ai-runtime'
$env:AI_RUNTIME_STATE_BACKEND = 'postgres'
$env:AI_RUNTIME_DATABASE_HOST = '127.0.0.1'
$env:AI_RUNTIME_DATABASE_NAME = 'opsmind'
$env:AI_RUNTIME_DATABASE_USER = 'opsmind_ai_runtime'
$env:AI_RUNTIME_DATABASE_PASSWORD = '<runtime-secret>'
$env:OPS_ENABLE_DEEPSEEK_EGRESS = 'true'
```

Readiness also requires an exact provider-host allowlist, positive approved input/output prices, an allowed outbound data class, the shared PostgreSQL state backend, and a local public JWKS file for strict RS256 verification. The Platform API private key is never shared with this service. Remote JWKS discovery and symmetric capability secrets are intentionally unsupported. Memory state is accepted only for offline tests or disabled provider mode. `AI_MAX_COST_USD_PER_RUN` defaults to USD 1. Request JSON is capped by `AI_RUNTIME_MAX_JSON_BODY_BYTES` (1 MiB by default) and must arrive within `AI_RUNTIME_BODY_RECEIVE_TIMEOUT_SECONDS`. A configured key alone is never a readiness or egress decision.

## Contract and tests

- `POST /api/v1/analysis` requires `X-OpsMind-Delegated-Capability` and returns the versioned analysis contract.
- The capability binds issuer, audience, tenant, incident, run, purpose, data classes, maximum lifetime, deadline, and the exact canonical request digest.
- Every evidence reference must carry source classification metadata matching the signed request; citations must resolve to an authorized evidence ID and content digest.
- Token and cost allowances are cumulative per run and constrain provider `max_tokens`; an ambiguous provider failure consumes the full reservation.
- A reservation lease never expires before the signed request deadline; the configured lease duration is a minimum for very short requests.
- Provider-specific payloads are isolated under `src/opsmind_ai_runtime/providers/deepseek/`.
- Reasoning content is transient and ignored by the adapter; it is not persisted or logged.
- Tool calls are normalized intents only. Phase 6 owns policy-gated execution.

Run the offline suite without any provider key:

```powershell
$env:PYTHONPATH = 'services/ai-runtime/src'
python -m pytest services/ai-runtime/tests -q
```

Run the disposable PostgreSQL state matrix only after the storage preflight passes and Docker Desktop storage has been verified on the monitored D-backed location:

```powershell
$env:OPS_DOCKER_STORAGE_VERIFIED = 'true'
powershell.exe -NoProfile -File scripts/validation/run-phase-05-local-postgres-state.ps1
```

The migration stores only nonce/request digests, bounded budget state, secret-free invocation metadata, and validated normalized responses. It never stores raw prompts, capabilities, credentials, provider reasoning, or provider error bodies.

The live smoke test is intentionally not part of the default command. It requires an externally injected rotated staging key, approved provider terms/region, synthetic redacted data, and immutable evidence publication.
