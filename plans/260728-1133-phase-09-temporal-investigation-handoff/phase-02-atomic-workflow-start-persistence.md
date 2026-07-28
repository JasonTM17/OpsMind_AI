---
phase: 2
title: Atomic workflow-start persistence
status: in-progress
effort: 1.5 days
---

# Phase 2: Atomic workflow-start persistence

## Overview

Create a transactionally atomic handoff while preserving the existing
`InvestigationRunStore` contract and inline orchestrator. Depend on the public
`OutboxRepository`; do not expose the package-private appender.

## Implementation Steps

### Tests first

1. Add migration contract assertions and PostgreSQL integration tests proving:
   - V010 applies on fresh and V009-upgrade paths.
   - `investigation_workflow_bindings` uses forced RLS and exact app/dispatcher
     grants.
   - injected failure after each insert rolls back run, reducer event/audit,
     binding, and outbox.
   - another tenant cannot read, claim, mutate, or infer a binding.
   - duplicate `(organization_id, run_id)` is create-or-load idempotent only for
     the same stored request digest; conflicting reuse returns 409.
   - workflow-start outbox rows cannot be forged: database validation binds exact
     event/schema/aggregate/payload identities to the authoritative run and
     immutable workflow binding.
2. Extend `InvestigationRunServiceTest` to prove inline remains synchronous and
   temporal mode returns the durable `CREATED` projection without invoking AI or
   Tool Gateway, and the controller emits additive `202 + Location`; inline
   remains the existing synchronous `200`.

### Production changes

3. Add `V010__investigation_workflow_start_handoff.sql` with:
   - immutable tenant/run/workflow/start-event binding;
   - `PENDING`, `STARTED`, and terminal `REJECTED` reconciliation states;
   - client request digest, payload digest, logical Temporal cluster ID,
     namespace, workflow type/task queue, authorization/incident revision,
     Temporal run ID, timestamps, and bounded error metadata;
   - forced RLS, tenant predicates, indexes, constraints, comments, and exact
     least-privilege grants/revokes for `opsmind_app`/`opsmind_dispatcher`.
   - an event-type-scoped tenant enumeration function and supporting partial
     index that share the exact candidate/predecessor selector with claim.
   - validation triggers/functions requiring exact workflow event/schema/
     aggregate/payload keys and equality with the binding and `investigation_runs`.
4. Add a canonical `InvestigationWorkflowStartRequest` record containing only
   organization/project/incident/run/actor IDs, budgets, start/deadline,
   logical cluster ID, namespace, workflow ID/type, and task queue. Validate all
   fields and serialize once.
5. Add `InvestigationExecutionStarter`:
   - `InlineInvestigationExecutionStarter` delegates to the current
     `InvestigationOrchestrator`.
   - `DurableInvestigationExecutionStarter` creates the initial reducer step and
     delegates to the handoff repository.
6. Add `JdbcInvestigationWorkflowHandoffRepository`. In one outer transaction it
   applies app tenant context and performs create-or-load:
   - attempt initial run/binding/outbox creation;
   - on conflict lock/read the persisted run and binding;
   - compare the client request digest and immutable identity, reuse persisted
     `started_at`, and return the existing projection only on an exact match.
   The canonical event still passes through `OutboxRepository`, but V010
   database validation—not Java digest alone—binds it to the run/binding.
   Never perform network I/O in this transaction.
7. Change `InvestigationRunService` to depend on `InvestigationExecutionStarter`.
   Wire `inline` as matching-if-missing and `temporal` only with persistence;
   never fall back when Temporal mode prerequisites are absent.
8. Add an additive OpenAPI/controller contract: inline POST remains `200`;
   accepted asynchronous handoff returns `202`, `Location` pointing at the
   existing scoped GET route, and the same bounded projection schema.
9. Add a cutover inventory/reconciliation command:
   - freeze new starts;
   - enumerate nonterminal `investigation_runs` without bindings;
   - backfill only revision-0 initial states whose ledger/request identity is
     provably complete;
   - block enablement and emit an auditable report for every other row;
   - require zero unresolved orphan rows before temporal admission.

## File Inventory

- Create `services/platform-api/src/main/resources/db/migration/V010__investigation_workflow_start_handoff.sql`.
- Create production classes under
  `services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/`.
- Modify `InvestigationRunService.java`,
  `InvestigationSliceConfiguration.java`, `InvestigationRunController.java`,
  `packages/contracts/openapi/opsmind-v1.yaml`, `application.yaml`,
  `.env.example`, `MigrationContractTest.java`, and focused investigation tests.

## Contract Details

- Workflow ID: `opsmind-investigation/{organizationId}/{runId}`.
- Event type/schema: `investigation.workflow-start.requested` / `1`.
- Aggregate: type `investigation-workflow`, ID `runId`, sequence `1`.
- Event ID is deterministic from tenant/run/event type; correlation ID is
  `runId`; payload bytes are hashed once with SHA-256 and reused.
- Temporal uniqueness scope is the persisted `(logical_cluster_id, namespace,
  workflow_id)`, never a mutable endpoint string.
- Client request digest excludes server-generated `started_at`; the binding
  stores the first accepted value and exact HTTP retries reuse it.
- Raw evidence, prompt text, incident title/description, tokens, capabilities,
  and provider data are prohibited fields.
- The binding carries identity and authorization revision, not bearer authority.
  Every future worker activity must resolve workload identity and reauthorize
  current membership/incident/evidence access before external work.

## Risks and Rollback

- Nested transaction accidentally commits the run before outbox: prove rollback
  with fault injection; outer transaction must own the commit.
- Property misconfiguration creates two starters: conditional-bean tests must
  prove exactly one or fail startup.
- Forward fix V010 only; never edit an applied migration.

## Success Criteria

- [ ] Atomic rollback and forced-RLS tests pass under real PostgreSQL roles.
- [ ] Inline behavior remains compatible and Temporal mode never calls inline.
- [ ] Canonical payload/digest/ID vectors are deterministic across retries.
- [ ] No prohibited data class exists in the serialized start payload.
- [ ] Forged same-tenant payloads fail in PostgreSQL before dispatcher visibility.
- [ ] Async OpenAPI compatibility and zero-orphan cutover gates pass.
