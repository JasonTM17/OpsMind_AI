---
phase: 3
title: Controlled Ingress and Lifecycle Operations
status: pending
priority: P1
dependsOn: [phase-02]
---

# Phase 03: Controlled Ingress and Lifecycle Operations

## Objective

Connect a narrowly authorized producer to the artifact port, then complete
the operational lifecycle: scan gating, holds, deletion request/purge receipt,
orphan reconciliation, and restore/reconciliation evidence. This phase may
start only after a supported backend/local supply-chain decision, an approved
credential model, and operational owners are available.

## File Ownership

| Area | Planned responsibility |
|---|---|
| Platform artifact ingress/auth/lifecycle worker | capability and workload authorization, lifecycle transitions, audit/outbox/reconciliation |
| Tool Gateway contract/client | bounded artifact upload protocol; never direct arbitrary bucket credentials |
| `packages/contracts/**` | versioned internal transport schema and compatibility tests |
| Scanner/retention/reconciliation adapters | policy-gated scan, hold/delete/purge receipt, inventory reconciliation |
| Runbooks/CI | recovery, deletion pause, restore drill, supported-backend and supply-chain evidence |

## Required Preconditions

- A supported S3-compatible backend decision that resolves or bounds B-012.
- KMS/key-ownership, scanner, residency, retention/deletion, and backup owner
  decisions required by ADR-0003.
- A capability-bound producer identity; no direct object-store credential can
  be handed to Tool Gateway or a browser.

## Test Scenario Matrix

| Scenario | Required proof |
|---|---|
| Gateway artifact result | Platform verifies capability/scope/digest/length; no raw body in event/audit/prompt |
| Scan required/clean/malicious/unavailable | Availability only when policy permits; quarantine or fail closed otherwise |
| Membership/incident authorization drift | Read blocked immediately despite retained object |
| Hold/deletion/purge | Legal hold blocks deletion; purge produces durable receipt; async physical state stays auditable |
| Crash windows | Before/after storage and finalization residues are retried or classified without false availability |
| Restore/reconcile | Metadata/object inventory mismatch detected; target/rto evidence retained |

## Acceptance Criteria

- [ ] Cross-service artifact protocol is versioned, capability-bound, and
  independently reviewed before the old artifact-reference rejection changes.
- [ ] Lifecycle transitions, scan, hold, deletion, purge receipt, and orphan
  reconciliation have deterministic tests and operational runbooks.
- [ ] A supported backend and production KMS/restore evidence closes only the
  corresponding blockers; no CI fixture alone is labeled production proof.
