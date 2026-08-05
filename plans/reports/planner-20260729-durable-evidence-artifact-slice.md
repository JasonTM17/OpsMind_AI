## Durable evidence artifact slice — planning summary

Decision: create a child execution plan under Phase 4, not a parent-plan edit
only.

Why:

- Phase 4 parent already owns the full scope and exit gate:
  `phase-04-incident-control-plane-and-audit-ledger.md:23-33`, `:227-238`.
- Phase 4B is already completed and intentionally fixed to bounded inline
  records only:
  `phase-04b-bounded-evidence-record-ingress.md:22-24`, `:49-55`.
- Current code rejects non-inline artifact paths:
  `CollectedEvidence.java:42-58`,
  `ToolGatewayResponseValidator.java:95-116`,
  `InvestigationAnalysisPromptAssembler.java:36-60`.
- V007 is hard-coded inline-only and must remain so:
  `V007__bounded_evidence_records.sql:12-67`.

Recommended shape:

1. Keep V007 unchanged.
2. Add V014 for artifact metadata/lifecycle authority.
3. Add application-owned create/upload/finalize/read port with object I/O
   outside DB transactions.
4. Use fail-closed default adapter plus test-only filesystem adapter only.
5. Add read/tombstone/restore/purge-receipt/reconciliation shell.
6. Add dedicated validators and docs sync.

What this slice can claim:

- Progress on B-006 and B-008 runtime structure.
- Metadata authority, lifecycle shell, auth-epoch enforcement, reconciliation
  seams, and test-only adapter proof.

What this slice cannot claim:

- Supported production backend decision or B-012 closure.
- `production-kms` conformance.
- Malware scanning proof.
- Restore drill proof or B-011 closure.
- Provider-visible large-object prompt path.

File ownership groups:

- Phase 1: migration + `.../evidence/artifact/model/**`
- Phase 2: `.../evidence/artifact/port/**`, `.../upload/**`, `.../adapter/**`
- Phase 3: `.../evidence/artifact/access/**`, `.../lifecycle/**`,
  authorization call sites
- Phase 4: validation scripts + docs only

Parallelism:

- Do not parallelize this slice initially. The migration, authorization seam,
  and evidence package are shared hot spots.

Plan created:

- `D:\OpsMind_AI\plans\260729-1800-durable-evidence-artifact-slice\plan.md`

Status: DONE
Summary: Child plan created. Sequential four-phase slice. Preserves V007 and keeps B-012/B-011 explicit.
Concerns/Blockers: Supported backend decision, KMS proof, malware scan, restore drill, and positive cross-service artifact contract remain out of scope and blocked.
