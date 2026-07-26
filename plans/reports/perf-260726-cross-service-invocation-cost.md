# Cross-service invocation cost

Question: the process supervisor made every harness invocation slower. How much,
where does it go, and how much of it is real?

## Correction to earlier figures

Earlier notes in this project recorded 17-31 s and then 8-15 s per invocation and
attributed them to the supervisor. Those measurements were taken in WSL and
overstate the CI cost several-fold.

WSL inherits 51 Windows directories on `PATH`, and
`Get-Command -CommandType Application` enumerates `PATH` on every call over the
9p filesystem. Measured individually: `sh` 4.15 s, `setsid` 2.39 s, `kill`
1.94 s, `sleep` 1.95 s. The harness resolved five or six executables per
invocation, so local timings carried 10-20 s that a native runner never pays.

Phase timing confirmed it: almost the whole local cost landed before the
supervisor published its started marker, which is the resolution window rather
than anything the supervisor does with the child.

## The real regression

Taken from CI job duration, which is the only environment that matters for this
claim:

| Revision | Cross-service job |
|---|---|
| Before the supervisor | 4m22s |
| After the supervisor | 7m13s to 8m15s |

About three minutes across roughly forty invocations. That is real and it is
attributable to the supervisor's design — an additional PowerShell process per
invocation, polling, and ordered teardown — not to executable resolution.

## What the memoisation actually bought

Resolution is now cached for the run. Local Linux invocations fell from 33.8 s
and 24.7 s to 8.7 s and 7.4 s once the cache is warm.

The CI job after that change ran 7m28s, inside the same range as before it. **The
optimisation is a local development improvement and a no-op on CI**, which is the
expected result: a native `PATH` makes the lookup cheap, so there was nothing
there to save.

It was still worth making. Every local measurement of this harness was being
distorted by it, including the ones that produced the wrong regression figures
above.

## Remaining cost

Warm local phase timing puts roughly 4.4 s in launch and 2.4 s in teardown. On a
native runner both are smaller but proportional, and teardown is the larger
share of what a redesign could recover. That is the supervisor author's design
space: the ordering exists to guarantee process ownership, and trading it for
speed is their call, not a defect to fix unilaterally.

## Unresolved questions

- Is roughly three CI minutes an acceptable price for guaranteed descendant
  cleanup? The job is well inside its 90-minute limit, so this is a preference
  rather than a constraint, and the supervisor's author should decide.
