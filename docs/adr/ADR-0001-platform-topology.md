# ADR-0001: Initial Platform Topology

- Status: Accepted
- Date: 2026-07-19
- Decision owners: Architecture governance with the G0.5 operational owner roles

## Context

OpsMind spans web UX, transactional incident control, model integration, privileged connectors, durable workflows, retrieval, evaluation, and operations. Splitting every concern into a service before a working slice would multiply contracts, deployments, failure modes, and observability cost. Keeping connector credentials inside the main API would weaken the most important trust boundary.

## Decision

Start with four deployables:

1. Next.js Operator Web.
2. Java 21 Spring Platform API implemented as a modular monolith.
3. Python FastAPI AI Runtime with provider-neutral model adapters.
4. Java 21 Spring Tool Gateway isolated from the Platform API and AI Runtime.

Use PostgreSQL as transactional source of truth and pgvector for the first retrieval implementation. Redis is optional and must have a measured need. Use transactional outbox/inbox before Kafka. Implement the incident investigation as a pure deterministic state machine in the vertical slice. Introduce Temporal in Phase 9 through an adapter after state-machine and evaluation proof.

The simulator is local/CI/staging test infrastructure only and is excluded from production deployments.

## Component Responsibilities

| Component | Owns | Does not own |
|---|---|---|
| Operator Web | Presentation and operator interaction | Authorization authority or secrets |
| Platform API | Identity-derived scope, incidents, policy, approval, audit, capabilities, transactions | Connector credentials or model reasoning |
| AI Runtime | Prompt/evidence assembly, provider calls, response validation, model budgets | Broad tenant queries or external writes |
| Tool Gateway | Connector credentials, policy enforcement, dry-run, target CAS, receipts/reconciliation | Caller-defined authority or incident truth |

## Consequences

Positive:

- Fewer deployables and contracts while product behavior is still changing.
- Strong isolation for privileged connector credentials and effects.
- Java owns policy/execution boundaries; Python owns fast-changing model integration.
- Deterministic domain behavior remains testable without Temporal.
- Service extraction can be driven by evidence.

Costs:

- The modular Platform API requires architecture-boundary enforcement.
- Two languages require shared generated contracts and coordinated CI.
- Tool Gateway isolation creates an early authenticated service boundary.
- Temporal integration later requires adapter discipline and replay tests.

## Alternatives Considered

### Microservices from the start

Rejected because the approved internal single-organization load envelope and
six-contributor delivery model do not justify distributed transactions and
operational load before a measured vertical slice.

### One application including AI and tools

Rejected because it combines probabilistic parsing, transactional authority, and infrastructure credentials in one compromise boundary.

### Event streaming first

Rejected until outbox throughput, ownership, replay, or independent consumer needs justify Kafka.

## Verification

- Phase 2 architecture tests prevent forbidden dependencies and competing deployables.
- Phase 7 proves the deterministic state machine and thin slice.
- Phase 9 proves Temporal replay/versioning without changing domain transitions.
- Security tests prove AI Runtime lacks connector credentials and Tool Gateway rejects unverified authority.

## Rollback or Supersession Triggers

Create a superseding ADR when measured load, regulatory isolation, independent ownership, fault containment, language/runtime constraints, or release cadence makes a service extraction necessary. Do not split solely by conceptual component count.

## Unresolved Questions

Managed Kubernetes in `ap-southeast-1` with Singapore residency is approved.
Replica topology, network policy, autoscaling settings, and concrete supported
managed-service products remain implementation decisions that must conform to
the approved envelope.
