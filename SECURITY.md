# Security policy

## Reporting a vulnerability

Please report suspected vulnerabilities privately through [GitHub Security Advisories](https://github.com/JasonTM17/OpsMind_AI/security/advisories/new). Do not include credentials, access tokens, customer data, raw incident payloads, or exploit details in a public issue or pull request.

If private advisories are unavailable, contact the repository owner through a private GitHub channel and include “OpsMind AI security report” in the subject. Do not send secrets in the first message; request a secure exchange channel instead.

## What to include

- affected revision, release, or immutable image digest;
- impact and trust boundary affected;
- minimal reproduction using synthetic data;
- sanitized logs, correlation IDs, and evidence artifact paths;
- any mitigation already applied.

## Supported versions

Only the latest published release and the current default branch receive active security fixes while the project is under active development. Local/reference evidence is not a production support commitment.

## Security invariants

- Secrets are supplied through environment or an approved secret manager, never committed.
- Authorization and tenant context are enforced before retrieval and action execution.
- Read-only investigation is the default; mutations require policy, exact approval, idempotency, reconciliation, and audit.
- Evidence and audit artifacts are sanitized and bounded.
