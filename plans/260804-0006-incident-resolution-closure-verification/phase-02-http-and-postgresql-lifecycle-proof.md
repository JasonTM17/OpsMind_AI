---
phase: 2
title: HTTP and PostgreSQL lifecycle proof
status: completed
priority: P1
dependencies:
  - 1
---

# Phase 2: HTTP and PostgreSQL lifecycle proof

## Overview

Exercise the existing public transition boundary through a complete authenticated lifecycle against real PostgreSQL.

## Requirements

- Drive OPEN -> INVESTIGATING -> RESOLVED -> CLOSED with strong ETags and unique idempotency keys.
- Prove CLOSED keeps the root cause and resolution summary established at RESOLVED.
- Prove exact closure replay returns identical semantic response and creates no new effects.
- Prove stale ETag and post-close transition fail without aggregate/event/idempotency side effects.
- Preserve tenant isolation, role checks, audit linkage, outbox ordering, and immutable timeline behavior.

## Related Code Files

- Modify: `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentControllerHttpTest.java`
- Modify: `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentHttpPersistenceIntegrationTest.java`
- Modify: `.github/workflows/pr-quality.yml` to execute and upload the environment-gated lifecycle proof.
- Modify implementation files only if a failing test proves a real defect.

## Implementation Steps

1. Add controller binding coverage for a valid CLOSED body and reject supplied resolution fields.
2. Extend the PostgreSQL HTTP test with a deterministic transition helper and full lifecycle.
3. Capture row counts for timeline, audit, outbox, and idempotency before negative operations.
4. Assert closure replay identity and unchanged counts.
5. Assert detail and timeline expose CLOSED with retained resolution values.
6. Assert stale and terminal transitions return contract errors and unchanged counts.

## Success Criteria

- [ ] Public HTTP lifecycle reaches CLOSED with monotonic ETags.
- [ ] Closure replay is byte/ETag/operation-ID identical.
- [ ] CLOSED is terminal at the public persistence boundary.
- [ ] Negative operations append zero durable effects.
- [ ] No production route/state-machine/migration change unless tests expose a defect.

## Risks

- Environment-gated tests may be skipped locally. Mitigate by explicit CI job inspection and test-count evidence.
- Shared test fixture complexity. Prefer small helpers inside the existing integration class.
