# Evaluation

This directory owns versioned, secret-free evaluation contracts and synthetic
scenario inputs. It is not a training export and must never contain customer
telemetry, provider credentials, hidden reasoning, or production evidence.

## Current checkpoint

Phase 8A implements the contract foundation and the first deterministic smoke
family, `deployment-latency-regression`. The scorer consumes the real Phase 7
cross-service trace. It scores display-safe operator semantics separately from
semantic RCA; the latter requires a trusted raw-analysis artifact. It fails
closed with `INCOMPLETE` when the trace predates required operator-projection,
raw-analysis, or tool-receipt artifacts.

This checkpoint does not establish production RCA accuracy, p95 latency,
calibration, human benefit, or Phase 8 completion. The provider, held-out,
human-review, Scenario B/C, and release-scale gates remain open.

## Run

After the storage preflight and a fresh Phase 7 cross-service trace:

```powershell
pnpm evaluate
```

Generated reports remain under `OPS_ARTIFACT_ROOT/evaluation/phase-08/` and are
ignored by Git. The committed scenario fixtures stay small, deterministic, and
explicitly ineligible for training.
