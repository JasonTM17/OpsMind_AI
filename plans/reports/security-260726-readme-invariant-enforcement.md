# Invariant enforcement sweep

Question: the `README.md` non-negotiable invariants are strong claims. Which of
them are enforced by code, and which are only asserted in prose?

This sweep was prompted by a recurring defect class found earlier the same day:
a control described in documentation with nothing in the code path enforcing it.
The human baseline record schema was the clearest instance — the protocol stated
a submission could not carry incident narrative "because there is nowhere to put
it", while the schema that would have prevented it was never applied.

## Result

The production invariants are enforced. Every defect of this class found today
was in code written the same session and not yet independently reviewed, not in
the established service layers.

| Invariant | Enforcement | Evidence |
|---|---|---|
| 2. The model cannot access infrastructure credentials | Code | `InvestigationAnalysisPromptAssembler` references no credential, secret, key, or password; its only token reference is the numeric `remaining_token_budget`. |
| 4. Read-only investigation is the default | Type construction | `ToolManifest` rejects construction when `readOnly` is false or `riskClass` is not `read-only`, so a mutating manifest cannot exist as an object. |
| 5. Tenant and actor come from verified claims, not callers | Absence | No source file derives tenant, organization, or actor from a request header; there is no code path to disable. |
| 6. Heavy local work fails closed on unsafe storage | Runtime | The capacity preflight blocked this session's local suite repeatedly and refused to run rather than degrade. |
| 7. No credential or raw sensitive prompt is committed | Gate | `scan-project-secrets.ps1` reports `Findings=0` over 5.8 MB of scanned history. |

## Interpretation

Invariant 4 is the strongest of these. Enforcing it in the compact constructor
means the check cannot be forgotten at a call site, because there is no valid
object to pass. That is the shape the human baseline record contract now takes
as well: validated at ingestion rather than trusted from a document.

Invariant 5 is enforced by absence rather than by a check. That is durable while
it holds, and silent if it ever stops holding. A test asserting that no request
header can influence tenant resolution would make a future regression loud, but
adding one now would be speculative: there is no such path to guard against.

## Not covered

Invariants 1, 3, and 8 are process and design properties spanning several
services. Verifying them requires tracing complete request flows rather than
inspecting a boundary, which this sweep did not attempt.

## Unresolved questions

None.
