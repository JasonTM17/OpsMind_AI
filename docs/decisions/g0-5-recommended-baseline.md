# G0.5 Recommended Baseline

## Overview

This is a concrete, machine-valid proposal for review. It is not the authoritative contract and records no approval. The proposal remains `blocked`; all twelve decisions remain `pending` in [product-production-contract-recommended.json](./product-production-contract-recommended.json).

Phase 2 may start only after an accountable person explicitly accepts or changes these values, the approval is recorded in [the authoritative contract](./product-production-contract.json), and the strict validator returns `0`.

## Proposed Decisions

| Decision | Proposed value | Why this is the simplest credible start | Main trade-off / second-order effect |
|---|---|---|---|
| Deployment | Internal, single organization | Avoids premature managed-SaaS compliance and support scope | Later multi-organization delivery requires a separately evaluated expansion, not a feature toggle |
| Production target | Managed Kubernetes, `ap-southeast-1`, Singapore | One region and managed control plane reduce operational variables | Creates a cloud/region dependency; customer-hosted delivery needs different KMS, networking, support, and DR |
| Tenant model | One organization, logical isolation, up to 100 projects | Preserves tenant/project authorization contracts without physical fleet duplication | Logical isolation still requires forced RLS, cache/vector namespace controls, and adversarial isolation tests |
| Identity | Enterprise OIDC, Authorization Code with PKCE, MFA, security-owned break-glass | Uses established enterprise identity and avoids local password ownership | IdP availability and claims mapping become production dependencies; break-glass needs a drill and separate audit |
| DeepSeek egress | Only redacted metrics and redacted log summaries; no provider retention; USD 1,000/month; human-only fallback | Enables model evaluation without sending raw incident payloads or granting automatic fallback authority | If provider geography/processing terms cannot satisfy approval, mode must become `disabled`; the marketing model name is not an API contract |
| First live connector | Read-only Prometheus against synthetic non-production metrics | Smallest real evidence integration with low blast radius | It does not prove log, trace, Kubernetes, Git, or production connector behavior |
| Evidence store | MinIO local; S3-compatible production; production KMS; four-hour restore target | One object API supports local parity and production durability | Backend/KMS ownership, lifecycle, replication, and restore drills become release-critical |
| Load envelope | 1 organization; 25 concurrent investigations; 500 evidence events/s; 120 model requests/min | Bounded numbers make performance and budget tests executable | These are initial ceilings, not demand forecasts; G3 measurements must trigger re-estimation |
| Service objectives | 99.9% availability; API p95 500 ms; RTO 120 min; RPO 15 min | Concrete initial release and DR gates | Provider latency must be measured separately from API latency; meeting RPO requires backup/restore evidence |
| Data lifecycle | Incidents 365 days; evidence 90; audit 730; deletion within 24 h; training opt-in only; Singapore | Separates operational, evidence, and accountability retention | Deletion must propagate through RAG indexes, exports, datasets, checkpoints, and caches without deleting required audit proof |
| Ownership | Named role-level owners for platform, on-call, security, privacy, integrations, database, workflow, and provider spend | Prevents controls with no operator or budget owner | Role identifiers must be mapped to real accountable people before production on-call and risk acceptance |
| Delivery | 6 contributors, 9 months, funded, six core skill areas | Credible minimum cross-functional program for the full A–Z scope | It remains a planning envelope; vendor/legal lead time and 25–35% contingency require re-estimation after approval |

## Conditions Attached to Approval

- No provider credential is stored in the repository, client bundle, image, evidence, prompt fixture, or log.
- DeepSeek traffic remains disabled until model-ID/API conformance, processing terms, region, retention, redaction, and budget controls pass.
- Read-only incident investigation ships before any remediation write path.
- Every action later binds policy, exact preview digest, approval, target state, idempotency, audit, and reconciliation.
- Multi-tenant authorization precedes evidence retrieval, RAG filtering, generation, export, and tool execution.
- The numeric load/SLO/lifecycle values are approved initial gates and must be revised through measured evidence rather than silently relaxed.
- `platform-team`-style owners are accountable role identifiers; production requires mapping each role to a real person/on-call group.

## Approval Response

To accept all proposed values, reply with:

```text
I approve the OpsMind G0.5 recommended baseline in full.
Approver identity and role: <name / accountable role>
Exceptions: none
```

To change anything, use:

```text
I approve OpsMind G0.5 with these changes:
- <decision key>: <replacement value and reason>
Approver identity and role: <name / accountable role>
```

Approval authorizes recording the stated product/production decisions. It does not authorize production deployment, external writes, destructive cleanup, or committing a disclosed API key.

## After Approval

1. Create a dated decision record containing the exact approved values and approver identity/role.
2. Copy only approved values into the authoritative contract; set per-decision and global approval timestamps/evidence.
3. Run the strict contract validator without `-AllowPending`.
4. Revalidate architecture, threat model, phase dependencies, estimates, and traceability against the approved profile.
5. Start Phase 2 only if capacity/root/secret/documentation gates also pass.

## References

- [Product and Production Contract](./product-production-contract.md)
- [Contract schema](./product-production-contract.schema.json)
- [Active blockers](../blockers.md)
- [A–Z implementation plan](../../plans/260719-1747-opsmind-ai-production-platform/plan.md)

## Unresolved Questions

- Who is the accountable approver identity/role for the full baseline?
- Are the Singapore target, provider processing terms, USD 1,000 monthly ceiling, six-person staffing, and nine-month envelope acceptable?
