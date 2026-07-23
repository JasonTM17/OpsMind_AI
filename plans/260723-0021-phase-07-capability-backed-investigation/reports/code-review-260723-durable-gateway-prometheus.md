# Code Review: Durable Gateway and Prometheus

## Scope

- Tool Gateway durable nonce, receipt, audit, and transaction adapters
- Prometheus query catalog, HTTP transport, parser, manifest, and readiness
- PostgreSQL roles/migration, Compose topology, CI contracts, and launchers
- Focus: concurrency, failure classification, trust boundaries, data exposure,
  cancellation, and operational recovery

## Overall Assessment

Implementation passed revision-bound PostgreSQL, Compose, dependency-security,
and service proof in GitHub Actions run `29987371420`. No known blocking source
defect remains for this checkpoint.

## Findings Fixed

1. **High — lease decisions used application time.** Claim/reclaim and active
   lease checks now use PostgreSQL transaction time. Signed request deadline and
   configured duration are combined in SQL; stale owners remain token fenced.
2. **High — persistence failures could report connector failure.** Claim,
   transaction, receipt completion, success audit, and replay audit failures now
   map to their durable-store or audit denial codes. Regression tests cover
   claim and completion failure classification.
3. **High — configurable multiple series could be silently discarded.** The
   connector now enforces one exact series, matching its canonical evidence
   shape and expected label identity.
4. **Medium — large classes mixed protocol and persistence coordination.**
   Prometheus response DTOs, JDBC receipt row mapping, and durable execution
   coordination now have separate bounded responsibilities.
5. **Medium — provision container depended on an image-specific numeric user.**
   Compose now resolves the image's `postgres` account and uses a guarded
   writable temporary directory.
6. **Low — bootstrap comment described init-only behavior.** It now documents
   the idempotent one-shot provisioner for fresh and existing data volumes.

## Production-Risk Review

- Concurrency: row lock, database-clock lease, conditional reclaim, token-fenced
  completion, digest/scope conflict, and concurrent-claim tests present.
- Error boundaries: connector, timeout, cancellation, result oversize, receipt,
  transaction, and audit failures remain explicit and fail closed.
- API contracts: existing request/response schema and manifest version retained.
- Backwards compatibility: migration is additive and forward-only.
- Input validation: callers cannot supply URL, path, PromQL, labels, credential
  profile, HTTP verb, or arbitrary query parameters.
- Auth and authorization: workload and delegated tokens remain separate; DB
  migration/runtime roles are fixed and Platform/AI roles receive no grants.
- Query efficiency: receipt claim is indexed by execution ID; expired nonce and
  lease paths have indexes; no DB transaction spans HTTP.
- Data leaks: nonces are hashed; tokens, headers, credentials, prompts, and raw
  provider payloads are not persisted; HTTP response and stored response sizes
  are bounded.

## Verification

- Tool Gateway Maven: 49 tests, 0 failures, 0 errors, 6 guarded PostgreSQL skips
- Focused durability/Prometheus tests: 13 passed
- Repository, Phase 6, Phase 7, and Prometheus fixture validators: pass
- Compose application-profile config, Bash, PowerShell, and YAML syntax: pass
- Documentation validator: links/config confirmations pass; heuristic
  code/config warnings are pre-existing false-positive categories
- GitHub Actions `29987371420`: success on commit `ace3642`

## Unresolved Questions

None for this checkpoint. Phase 7 cross-service trace, p95, and CK/Stitch UI
proof remain separate planned work.
