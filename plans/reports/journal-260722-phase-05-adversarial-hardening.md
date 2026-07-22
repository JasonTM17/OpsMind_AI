# Phase 5 Redaction and Readiness Audit: The Gaps We Should Have Caught

**Date**: 2026-07-22  
**Severity**: High  
**Component**: AI Runtime egress policy, Platform API evidence redaction, provider capability readiness  
**Status**: Resolved with concerns

## What Happened

The adversarial review found two trust-boundary failures in code that looked finished. Redaction covered snake_case and hyphenated keys, but not camelCase keys such as `apiKey`, `bearerToken`, or `authorizationHeader`; both Python and Java could leak credential-shaped text. Separately, a shared positive probe cache let one replica's success stand in for another replica's unproven path. Five-minute dedupe and application-clock windows created readiness/quota races during rollout.

## The Brutal Truth

This was exhausting because the happy-path suite was green while dangerous inputs were absent. We tested our preferred spelling and topology, then called the boundary safe. The review forced a release stop, but that pain was cheaper than sending evidence or provider traffic under false readiness. No provider key was loaded or logged; the remaining block is deliberate.

## Technical Details

The Python and Java regexes now share structured-key coverage and mirrored canary tests. The review records Python **149 passed** (five PostgreSQL-gated skips), Maven focused and full suites passing, and PostgreSQL 18.4's five-state tests passing, including the concurrent quota race and append-only audit privilege denial. Probe admission now uses `pg_advisory_xact_lock`, `transaction_timestamp() - interval '1 hour'`, and an append-only `started`/terminal audit. Startup and retry probes use bounded jitter. The default `AI_PROVIDER_PROBE_MAX_CALLS_PER_HOUR=120` supports roughly ten continuously probing replicas at a 300-330 second interval.

## What We Tried

- Kept the original key-pattern enumeration: it missed camelCase variants.
- Relied on a shared success cache and five-minute app-clock dedupe: it made cross-replica proof and race windows possible.
- Replaced both assumptions with mirrored canaries, per-process probes, an advisory transaction lock, DB-clock quota, and jitter.

## Root Cause Analysis

The root cause was enumeration bias plus an invalid ownership assumption. We treated naming variants as cosmetic and provider readiness as a cacheable global fact. Neither is true at a trust boundary: spelling variants are attacker-controlled input, and each replica must prove its own transport, model, and credentials.

## Lessons Learned

Security tests need a maintained adversarial corpus, not one representative secret. Readiness must be local to the process and serialized by shared state only for admission/quota, never for proof. A green test run without replica, clock, and cancellation scenarios is not release evidence.

## Next Steps

- AI Runtime owner: set probe quota from the documented replica formula before scaling past ten replicas; monitor quota denials and orphaned `started` rows.
- Platform API and Runtime security owners: keep the Python/Java canary corpus mirrored whenever credential keys change; review before the next release candidate.
- Release/compliance owner: obtain B-004 provider region/terms/retention/redaction evidence and rotated staging synthetic smoke evidence before G2/release. Phase 5 remains blocked until then.

Status: DONE
Summary: Recorded the Phase 5 adversarial hardening failure, fixes, evidence, decisions, and ownership actions.
Concerns/Blockers: B-004 external evidence and rotated-key live synthetic proof remain absent; cancellation audit completion is best effort during database outage.
