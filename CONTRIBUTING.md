# Contributing to OpsMind AI

OpsMind AI is built as an evidence-first AI SRE/DevSecOps platform. Contributions must preserve the trust boundary: models may recommend, but policy, approval, authorization, and audit control every external effect.

## Before opening a change

1. Read [the project overview](docs/project-overview-pdr.md), [system architecture](docs/system-architecture.md), [security model](docs/security-model.md), and [testing strategy](docs/testing-strategy.md).
2. Follow the CK workflow for the change: scout → plan → cook → test → code review → ship. Frontend work also follows the CK frontend workflow and may use Stitch for design artifacts.
3. Run the storage preflight before dependency installation, builds, containers, migrations, or benchmarks:

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\storage\check-capacity.ps1
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\storage\assert-storage-roots.ps1 -CreateMissing
   ```

4. Never put API keys, tokens, private keys, database passwords, customer data, or raw sensitive prompts in source, fixtures, evidence, images, or commit messages.

## Change expectations

- Keep changes scoped and modular; preserve public contracts unless the change explicitly updates them.
- Validate untrusted input at the service boundary, not only in the UI.
- Add tests for the happy path and the most important denial, conflict, timeout, and rollback paths.
- Record architecture, security, deployment, or public-contract changes in `docs/`.
- Evidence must be reproducible, sanitized, bounded, and must not be described as release proof when it is only local/reference evidence.

## Pull requests

Use the pull request template. Include exact verification commands, artifact paths, capacity results, and unresolved questions. A maintainer may request a plan or a narrower vertical slice before implementation.

## Security issues

Do not open a public issue for a vulnerability. Follow [SECURITY.md](SECURITY.md) and use GitHub's private advisory flow.
