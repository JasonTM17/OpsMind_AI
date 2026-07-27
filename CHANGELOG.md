# Changelog

All notable changes to this project are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project aims at
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

No release has been tagged. Every entry below is unreleased work, and the
project does not claim production readiness. What each phase has actually proven,
and what it explicitly has not, is recorded in
[`docs/progress.md`](docs/progress.md); open gates are listed in
[`docs/blockers.md`](docs/blockers.md).

## [Unreleased]

### Added

- Operating envelope: storage capacity and root preflights that fail closed
  before any heavyweight local work, plus the canonical documentation set.
- Identity and tenant foundation: OIDC verification, forced row-level security on
  tenant-scoped tables, transaction-local tenant context, and separate database
  roles that cannot bypass policy.
- Incident control plane with an immutable timeline, database-computed audit
  chaining, optimistic concurrency, and a transactional outbox.
- Provider-neutral AI Runtime with a DeepSeek adapter, delegated capability and
  egress controls, durable PostgreSQL state, and an append-only capability probe
  audit.
- Isolated Tool Gateway with one-use signed capabilities, immutable manifest
  resolution, durable receipts and audit events, and a read-only Prometheus
  connector.
- Evidence-backed investigation slice: bounded reducer, durable run snapshots,
  bounded evidence records, and an operator console that renders only an
  authorized projection.
- Deterministic evaluation baseline: three training-ineligible scenario families
  scored against machine-readable ground truth on the real service path, with
  digest-bound artifacts and revision-bound CI.
- Held-out corpus governance, a preregistered statistical protocol with Wilson
  intervals, and a human-baseline protocol. All three report `UNAVAILABLE` with a
  reason until cases and reviewers exist.

### Changed

- Benchmark results separate cases from trials. One scenario replayed a hundred
  times reports one case and a hundred correlated trials, so a stability
  measurement can no longer read as accuracy across incidents.
- Scenario cost budgets are bounded by the token budget priced at the configured
  rate, replacing a zero bound that no valid configuration could satisfy.

### Fixed

- An observed metric failure is no longer reported as missing evidence when
  another run contributes no observation.
- Prompt redaction and the evaluation value scan recognise secrets named in
  `SCREAMING_SNAKE_CASE` and credentials embedded in connection URIs.
- Reported provider cost is quantized to the scale of the column that stores it,
  so the durable value still equals the response it came from.
- Evaluation artifact references name the published trace rather than the
  transient path the projector writes to.

### Security

- Human-baseline records are validated against their contract at ingestion, so a
  submission cannot carry incident narrative through an undeclared field.
- Untrusted export keys and manifest identifiers are rendered rather than echoed
  into failure messages, which are written to evidence transcripts read line by
  line for status markers.
- A held-out corpus refuses entries sharing content or a payload path, so one
  observation cannot be registered as several.
