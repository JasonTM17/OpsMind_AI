# Phase 4C CI Regression

## Executive Summary
- Issue: job `90585498560` failed in step `Prove V006 to V013 upgrade, recovery, and reconciliation privileges`.
- Root cause: the V009 evidence gate failed its post-index p95 regression check in `incident-timeline-v009-evidence.sh`.
- Status: confirmed.
- Fix: investigate the V009 write-path regression; `V014` is not the trigger for this failure.

## Technical Analysis
- `scripts/validation/run-phase-04b-migration-upgrade.sh:428-429` sources and runs `run_incident_timeline_v009_evidence`.
- `scripts/validation/phase-04b-evidence-records/incident-timeline-v009-evidence.sh:216-219` enforces `post <= pre * 1.20`.
- Log evidence:
  - pre_index: `IncidentAppendHarnessP95Ms=2.019`, `InvestigationAppendHarnessP95Ms=4.589`
  - post_index: `IncidentAppendHarnessP95Ms=8.745`, `InvestigationAppendHarnessP95Ms=7.854`
  - cleanup still printed `CleanupResult=PASS` before shell exit `1`
- Result: first `awk` gate at line 216 fails because `8.745 > 2.019 * 1.20`; the second gate would also fail.

## Recommendations
- P0: inspect the V009 index/migration path for write amplification.
- P1: if the 20% envelope is still the contract, keep the gate and fix the migration; otherwise relax the benchmark threshold explicitly.

## Unresolved Questions
- None.
