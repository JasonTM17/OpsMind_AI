# Evaluation Transcript Boundary Failed Before Filesystem Access

**Date**: 2026-07-27 05:38
**Severity**: High
**Component**: Evaluation value safety and human baseline resolver
**Status**: Resolved; verified by descendant-revision CI

## What Happened

Commit `e0fbd96` stopped malicious JSON keys from forging evaluation transcript lines, but its shared `safeIdentifier` called `String(value)` twice. A stateful object could return `ok` for the regex check, then `ok\nResult=PASS\nCrossServiceVerification=PASS` for the returned value.

The first correction removed helper coercion, yet `resolveHumanBaseline` still sorted unvalidated directory entries, called `String(rawName)` during path construction, and exposed native filesystem errors. The adversarial review proved the green tests were phantom: they checked sanitized helper output, not the side effects that occurred before rejection.

## The Brutal Truth

We sanitized what an error might print instead of validating what the system was about to use. That is a basic trust-boundary mistake. It was frustrating to watch green tests certify the wrong branch, and relieving that the adversarial case exposed it before the transcript became release evidence.

## Technical Details

Before the final fix, a crafted entry coercing to `missing\nResult=PASS\nCrossServiceVerification=PASS\nx` reached `path.resolve` and could surface inside native `ENOENT` text from `fs.lstatSync`. After the fix, the same stateful entry performs zero coercions and fails with code `HUMAN_BASELINE_PATH` and message `Human baseline record name is invalid.` A legitimate absent `missing.json` is wrapped as `Human baseline record is unavailable: missing.json.`

The resolver now requires an array listing, validates every filename as a bounded ASCII `.json` string before sorting or path construction, and wraps metadata/read failures. The focused evaluator suite passes 60/60.

The fix was pushed as `551a14ea3281029e35b9c8a0f8ca82f616c0a9ca`.
Its PR-quality run `30223535325` and cross-service run `30223535308`
were cancelled by the workflows' documented `cancel-in-progress` policy after
later pushes. Revision `9a431e2adf4fbceb7c17334d840a882c806a4f36` is a
verified descendant of that fix. On that exact descendant, PR-quality run
`30223851014` and cross-service run `30223851005` both succeeded; each workflow
ran `node --test evaluation/runner/*.test.mjs`.

## What We Tried

- Rejected output-only sanitization: it left coercion and filesystem access reachable.
- Rejected fixing only `safeIdentifier`: `String(rawName)` and native `ENOENT` still leaked the hostile name.
- Chose boundary validation before sort/path work, plus controlled filesystem failures.

## Root Cause Analysis

We treated names as display values, not executable inputs to sorting, path resolution, and filesystem calls. Tests asserted the final message while missing the native-error branch.

## Lessons Learned

Sanitize and validate before any side effect. Every wrapper around untrusted paths needs an adversarial native-error test, not merely a helper test.

## Next Steps

- CI/release owner: retain the fix SHA, descendant SHA, and both successful run IDs as one evidence chain; never relabel the superseded runs as successful.
- Evaluation owner: retain the stateful-coercion, unsafe-name, and controlled missing-file regressions permanently.

## Unresolved Questions

None.
