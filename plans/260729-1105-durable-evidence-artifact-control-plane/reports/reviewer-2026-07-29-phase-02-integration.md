# Phase 02 Integration Review

Date: 2026-07-29

Branch: `feature/artifact-object-lifecycle`

Scope: V015 upload fencing, S3 adapter, upload orchestration, tests, validator,
CI wiring, and operator documentation.

## Overall Assessment

Source review and lightweight gates pass after correcting blocking lifecycle
defects. The implementation is ready for revision-bound Maven and disposable
PostgreSQL CI. It is not production/backend conformance evidence, and Phase 4C
exit remains blocked.

## Blocking Findings Resolved

1. Post-PUT exact-EOF/digest or response-metadata mismatch previously settled
   `UNCERTAIN`. A later HEAD match could adopt quarantined residue as `STORED`.
   The adapter now emits `SOURCE_CONTRACT_MISMATCH`; orchestration settles it
   and `REMOTE_METADATA_MISMATCH` as `ORPHANED`; V015 denies automated reclaim.
2. Definitive `FAILED` settlement shortened only the artifact lease timestamp,
   not its attempt lease. The next claim failed tuple-integrity validation.
   Settlement now preserves both lease values; the attempt status permits an
   immediate non-probe retry.
3. Java lease configuration allowed a one-hour value while V015 allowed five
   minutes. Both now enforce five seconds through five minutes.
4. Literal `null` and overlong opaque S3 version references could cross the
   storage/database boundary. Java and V015 reject `null` and enforce 1,024
   UTF-8 bytes.
5. Probe authorization failures could be treated as proof that no object
   existed. Every non-404 probe failure now preserves possible residue and
   prohibits another write.
6. Request-time KMS aliases were compared directly with canonical response
   identifiers. Configuration now separates the request key identifier from
   the expected response reference.
7. Artifact current-attempt metadata lacked a reverse foreign key to the
   durable attempt row. V015 now enforces the composite reference.

## Verification

- `node scripts/validation/validate-phase-04c-evidence-artifacts.mjs`
  - `Errors=0`
  - `CheckpointResult=PASS`
  - exit remains `BLOCK` for B-006/B-008/B-012
- `bash -n scripts/validation/run-phase-04c-artifact-object-postgres-contract.sh`
  - pass
- `node --check scripts/validation/validate-phase-04c-evidence-artifacts.mjs`
  - pass
- `git diff --check`
  - no whitespace errors; Windows line-ending notices only
- Java artifact package production files
  - maximum 200 lines; no file exceeds project modularization threshold
- POM XML parsing
  - pass

The disposable PostgreSQL runner now includes explicit proof markers for:

- `FailedAttemptImmediateRetry=PASS`;
- `OrphanedAttemptReclaimDenial=PASS`;
- concurrent single-winner claim;
- expired-claim probe fencing;
- stale/cross-tenant denial;
- exact STORED replay;
- deferred audit rollback;
- direct-DML denial and authorization-epoch drift.

## Verification Limits

Local Maven, Docker, and PostgreSQL execution intentionally did not run because
the workspace drive remains below the repository's 20 GiB heavy-work threshold.
GitHub CI must execute those gates on the exact pushed revision before merge.
The static gate is not S3 backend, KMS, restore, scanning, or release evidence.

## Unresolved Questions

- Which maintained S3-compatible backend replaces or bounds the archived MinIO
  dependency for local conformance?
- Which production workload identity, bucket owner, canonical KMS key, lifecycle
  rules, restore drill, and deletion receipt close B-006/B-008/B-012?
