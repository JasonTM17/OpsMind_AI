## Code Review Summary

### Scope

- Files: 19 files from `ceaf6c6`/`a3639be`/`2dc6507`; 7 modified and 2 untracked controller files. Read dependent Platform API boot, Compose, Alertmanager, Prometheus, CI, and validator paths.
- LOC: about 1,600 added/changed LOC in reviewed worker/controller surface; observability files read as integration context.
- Focus: final Phase 9 integration, especially worker process isolation, pinned local Temporal CI, Compose selectability, local-only Alertmanager evidence, and static validator coverage.
- Scout findings:
  - `InvestigationTemporalWorkerConfiguration` is inside the normal Platform API component-scan tree.
  - The same worker-enable environment variable is injected into both `platform-api` and `investigation-temporal-worker` Compose services.
  - CI parses the worker profile but never runs it; Java restart coverage boots a Spring context, not the packaged Compose entrypoint.
  - The worker static validator checks a small prefix blacklist, not an allowlist of its permitted environment.

### Overall Assessment

BLOCK. The dedicated entrypoint, digest-pinned Temporal development server, restart-test gate, and local-only Alertmanager receipt are present. The isolation contract is nevertheless broken when the worker is enabled through Compose, and the claimed CI/evidence gates do not prove the containerized worker boundary or produce the mandated evidence manifest.

### Critical Issues

1. **Critical — worker enablement also starts a privileged worker in `platform-api`.**
   - Evidence: [`InvestigationTemporalWorkerConfiguration.java:9`](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/InvestigationTemporalWorkerConfiguration.java:9) is a component-scanned `@Configuration` under the [`PlatformApiApplication.java:7`](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/services/platform-api/src/main/java/ai/opsmind/platform/PlatformApiApplication.java:7) package. It instantiates the `SmartLifecycle` runtime whenever `opsmind.investigation.temporal-worker.enabled=true`. The controller injects that same `OPSMIND_INVESTIGATION_TEMPORAL_WORKER_ENABLED` setting into [`compose.yaml:254`](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/compose.yaml:254) `platform-api` and [`compose.yaml:317`](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/compose.yaml:317) `investigation-temporal-worker`.
   - Impact: selecting the worker by setting this environment variable starts two pollers. One executes in the full Platform API process with its database, AI, Tool Gateway, dispatcher, and reconciler configuration. Task routing can send durable work to that privileged process, directly violating the required Temporal-only worker trust boundary.
   - Fix: make worker configuration unreachable from `PlatformApiApplication` (for example, put/import it outside the API scan root), use an entrypoint-only activation contract, and do not pass worker activation/configuration to `platform-api`. Add a full Platform API context/Compose assertion that an enabled worker configuration cannot create `InvestigationTemporalWorkerRuntime` there.

### High Priority

1. **High — the mandatory CI evidence fields are not recorded in artifacts.**
   - Evidence: the plan requires commit SHA, UTC times, image references/digests, configuration/rule digests, and executed commands. The Temporal job only writes the Maven output and a tail of server logs at [`pr-quality.yml:779`](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/.github/workflows/pr-quality.yml:779)-[`pr-quality.yml:847`](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/.github/workflows/pr-quality.yml:847); the Prometheus/Alertmanager step only tees tool output at [`pr-quality.yml:980`](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/.github/workflows/pr-quality.yml:980)-[`pr-quality.yml:1023`](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/.github/workflows/pr-quality.yml:1023). No Phase 9 evidence writer emits the required metadata/digests.
   - Impact: a passing run cannot be tied to an exact revision or immutable runtime/config inputs. The repository cannot use this artifact to close the CI-live part of B-017.
   - Fix: write one bounded Phase 9 manifest before execution with `GITHUB_SHA`, UTC start/end, exact image references, SHA-256s for all mounted configs/rules, and command identifiers; append test/target/receipt results and verify required fields before upload.

2. **High — CI never executes the selected Compose worker.**
   - Evidence: [`pr-quality.yml:972`](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/.github/workflows/pr-quality.yml:972) runs `docker compose ... config --quiet` with the worker profile, but the only `up` at [`pr-quality.yml:1053`](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/.github/workflows/pr-quality.yml:1053) excludes `phase-09-temporal-worker`. The restart test invokes `InvestigationTemporalWorkerApplication.createApplication()` directly in [`TemporalWorkerTestApplication.java:26`](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/services/platform-api/src/test/java/ai/opsmind/platform/investigation/workflow/TemporalWorkerTestApplication.java:26), so it does not exercise the Dockerfile/JAR/Compose service environment.
   - Impact: CI can pass while the packaged worker is unselectable, receives the wrong environment, or no longer retains its no-DB/no-AI boundary. This is a missing acceptance proof, not a claim that the current `PropertiesLauncher` entrypoint is invalid.
   - Fix: add a bounded CI run that starts the worker Compose profile against the pinned local server, waits for its compatible poller, and inspects the running container's effective environment for an explicit Temporal-only allowlist. Stop it and retain sanitized proof.

3. **High — the Temporal static validator does not enforce the secret boundary it reports.**
   - Evidence: [`validate-phase-09-temporal-worker-conformance.mjs:163`](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/scripts/validation/validate-phase-09-temporal-worker-conformance.mjs:163) rejects only `POSTGRES_`, `OPSMIND_WORKFLOW_RECONCILER_DB_`, and `OPSMIND_AI_`. Its own success message calls the result an isolated process. The regex returns false for `SPRING_DATASOURCE_PASSWORD`, `DEEPSEEK_API_KEY`, `OPSMIND_TOOL_CAPABILITY_PRIVATE_KEY_PATH`, and `OPSMIND_INVESTIGATION_TEMPORAL_OBSERVER_API_KEY`.
   - Impact: a later edit can inject database or provider credentials into the worker service while the source validator and CI static gate remain green.
   - Fix: parse Compose structurally or inspect `docker compose config` output and enforce a strict worker environment allowlist. Include explicit negative fixtures for datasource, AI/provider, Tool Gateway/capability, dispatcher/reconciler, and observer credentials.

### Medium Priority

None.

### Low Priority

None.

### Edge Cases Found by Scout

- Worker activation is a shared deployment-wide variable, not scoped to its worker-only process.
- A syntax-valid Compose profile is not runtime-selectability proof.
- Prefix denylisting cannot protect a credential boundary with multiple naming schemes.
- Alertmanager evidence is correctly marked `CI_LOCAL_ROUTING_CONFORMANCE`; it uses `url_file` and does not claim external paging. No stale finding is raised against that local-only design.

### Positive Observations

- Prior review fixes verified: [`compose.yaml:311`](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/compose.yaml:311) selects the worker entrypoint through `PropertiesLauncher`; [`pr-quality.yml:800`](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/.github/workflows/pr-quality.yml:800) pins `temporalio/temporal` by digest and [`pr-quality.yml:827`](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/.github/workflows/pr-quality.yml:827) gates the restart test. These are not re-reported as defects.

### Recommended Actions

1. Block merge until the Platform API cannot instantiate the worker and the Compose activation variable is worker-process-only.
2. Execute the worker Compose profile in CI with a Temporal-only effective-environment assertion.
3. Replace the worker validator blacklist with a structural allowlist and negative fixtures.
4. Produce and validate the required revision-bound Phase 9 evidence manifest before treating B-017 CI-live evidence as complete.

### Metrics

- Type Coverage: not measured.
- Test Coverage: not measured.
- Static validators: 2 passed; observability Node tests 3/3 passed.
- Diff whitespace: clean (`git diff --check`).
- Maven/Docker: not run locally; C: free space is below the mandated 10 GiB threshold.

### Plan Follow-up

- Lane A source and the pinned-server test gate are present, but acceptance items for worker-only isolation and Compose selectability are not met.
- Lane B local Alertmanager routing is source-backed and explicitly local-only; external delivery remains correctly unproven.
- The plan remains `in-progress`. Do not mark B-017, Phase 9/G4, or the CI-live evidence contract complete until the critical boundary and required artifact fields are fixed and exact-head CI is green.

### Unresolved Questions

- No exact-head CI artifact was available in this worktree, so live Maven/Docker results and the eventual artifact content could not be independently verified.
