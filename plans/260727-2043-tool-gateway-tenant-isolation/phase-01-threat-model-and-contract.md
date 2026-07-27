---
title: Threat Model and Contract
status: completed
---

# Phase 1: Threat Model and Contract

## Context

Current Java code drops trusted tenant/project values immediately after
capability verification. Receipt queries select by global `execution_id`, audit
rows have no scope, and transaction boundaries establish no RLS context.

## Work

- [x] Identify the only trusted scope origin: returned `VerifiedCapability`.
- [x] Map nonce, claim, connector, replay, denial, finalization, and abandon
  transaction boundaries.
- [x] Preserve global nonce and `execution_id` compatibility.
- [x] Select separate scoped and unverified audit lanes.
- [x] Add `TenantProjectScope` and update persistence/audit/transaction contracts.
- [x] Add focused contract tests for verified versus unverified audit routing.

## Files

- `services/tool-gateway/src/main/java/ai/opsmind/toolgateway/application/`
- `services/tool-gateway/src/main/java/ai/opsmind/toolgateway/audit/`
- related application tests

## Validation

- Compile all Tool Gateway call sites.
- Existing response schema, denial codes, and controller status mapping remain
  unchanged.

Local evidence: main compile, test-compile, 13/13 reviewer-remediation tests,
and the full non-database suite pass.

## Risks

- Never construct trusted scope from `ToolExecutionRequest` before capability
  verification.
- A verifier exception yields unverified/global audit even when request fields
  look syntactically valid.
