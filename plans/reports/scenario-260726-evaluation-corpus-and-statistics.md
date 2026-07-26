# Scenario decomposition — evaluation corpus and statistics

Target: `evaluation/runner/held-out-corpus.mjs`,
`evaluation/runner/human-baseline.mjs`, `evaluation/runner/sampling-intervals.mjs`,
and the sampling block the scorer emits.

Dimensions analysed: input extremes, timing, scale, error cascades, data
integrity, compliance, business logic.

Dimensions skipped: user types, environment, authorization, integration, state
transitions. These modules are stateless resolvers with no user surface, no
browser, no third-party call, and no authorization decision; their only trust
input is an operator-configured root.

Every scenario below was probed against the running code before being recorded.
Two produced defects, and both are fixed with regressions.

## Scenarios

| # | Dimension | Scenario | Severity | Outcome |
|---|---|---|---|---|
| 1 | Data integrity | Three manifest entries reference one payload file under distinct identifiers | **High** | **Defect.** Resolved as three cases. Fixed: content digest and payload path must both be unique. |
| 2 | Error cascades | A filesystem fault escapes the resolver and reaches the validator findings | **Medium** | **Defect.** The raw message embeds the absolute path of an operator-configured root whose directory name can identify a customer. Fixed: contract failures keep their message, other errors report type and code. |
| 3 | Data integrity | Two entries share a content digest but different paths | High | Covered by fix 1. |
| 4 | Input extremes | `case_id` containing a newline reaches a failure message | Medium | Already fixed earlier the same day; regression present. |
| 5 | Input extremes | `byte_size` of zero, or a string where a number is required | Low | Refused by the manifest contract. |
| 6 | Scale | Zero registered cases, and every case quarantined | Medium | Both report `UNAVAILABLE` with a reason rather than success. |
| 7 | Scale | More than 2000 cases | Low | Refused by both the contract and the resolver bound. |
| 8 | Timing | Payload replaced between the size check and the digest read | Low | Digest comparison follows the read, so substitution fails closed. |
| 9 | Business logic | Wilson interval at zero trials, all successes, denominator one | Medium | Reports null bounds with a reason, never zero; width preserved at the extremes. |
| 10 | Business logic | One scenario replayed a hundred times | High | Reported as one case and a hundred trials with `independent: false`. |
| 11 | Compliance | A reviewer record carrying incident narrative in an undeclared field | Critical | Fixed earlier the same day: the record contract is applied at ingestion. |

### Summary

- Critical: 1 (fixed earlier)
- High: 3 (one new defect, fixed)
- Medium: 5 (one new defect, fixed)
- Low: 3 (already covered)
- Total: 11 scenarios across 7 dimensions

## The finding worth naming

Scenario 1 is the one that mattered. The entire statistical protocol exists to
stop a denominator being inflated by correlated repeats, and it says so
explicitly about run counts. The corpus itself accepted the same observation
registered three times under different identifiers, which produces exactly the
inflation the protocol forbids, by a route the protocol did not describe.

Unique identifiers were being treated as evidence of distinct cases. They are
not. The preregistration now states distinctness as a rule rather than leaving it
implicit in a resolver.

## Unresolved questions

None.
