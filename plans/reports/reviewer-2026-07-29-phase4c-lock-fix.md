# Phase 4C lock-fix review

## Verdict

No blocker in the PostgreSQL permission fix. Re-run the real-role PostgreSQL contract before claiming CI closure.

## Scope and evidence

- Reviewed pending diff in `V014__evidence_artifact_metadata.sql` and `validate-phase-04c-evidence-artifacts.mjs` (9 added, 2 removed LOC).
- GitHub run `30451461042`, job `90575694097`, failed inside `opsmind_validate_evidence_artifact_event_append()` because `opsmind_app` lacked permission for the query ending in `FOR KEY SHARE`.
- Fresh static validator: `Errors=0`, `CheckpointResult=PASS`.

## Findings

### No blocker — lock removal preserves the supported-role invariants

- The event trigger still binds `NEW.organization_id` and `NEW.actor_id` to the session tenant/actor before lookup ([migration:265](../../services/platform-api/src/main/resources/db/migration/V014__evidence_artifact_metadata.sql)). It then verifies the artifact's project, incident, run, actor, deterministic event ID, audit ID, lifecycle version/state, and timestamp ([migration:282](../../services/platform-api/src/main/resources/db/migration/V014__evidence_artifact_metadata.sql)).
- `opsmind_app` retains the required `SELECT` columns but not `UPDATE`; removing the locking clause turns the failing row lock into an ordinary permitted read ([migration:273](../../services/platform-api/src/main/resources/db/migration/V014__evidence_artifact_metadata.sql), [migration:563](../../services/platform-api/src/main/resources/db/migration/V014__evidence_artifact_metadata.sql)).
- No normal app-role concurrent mutation path exists: metadata update/delete/truncate triggers reject mutation, those privileges are revoked, RLS remains forced, and the event keeps its parent foreign key ([migration:337](../../services/platform-api/src/main/resources/db/migration/V014__evidence_artifact_metadata.sql), [migration:549](../../services/platform-api/src/main/resources/db/migration/V014__evidence_artifact_metadata.sql), [migration:577](../../services/platform-api/src/main/resources/db/migration/V014__evidence_artifact_metadata.sql)). The audit branch and its tenant advisory lock are unchanged ([migration:452](../../services/platform-api/src/main/resources/db/migration/V014__evidence_artifact_metadata.sql), [migration:521](../../services/platform-api/src/main/resources/db/migration/V014__evidence_artifact_metadata.sql)).

### Resolved before landing — validator guard is fail-closed

The final diff fails validation if it cannot locate the append-validation function and detects `FOR KEY SHARE` case-insensitively across whitespace. This keeps a future formatting-only change from silently restoring the unsupported lock requirement.

## Verification

- `node scripts/validation/validate-phase-04c-evidence-artifacts.mjs` — PASS, `Errors=0`.
- `git diff --check -- <reviewed files>` — no whitespace errors.
- The failing real-role PostgreSQL integration test was not rerun in this read-only review; it is the required final proof that the CI symptom is closed.

## Unresolved Questions

- Does the fresh PostgreSQL real-role contract now pass on the exact pending tree?
