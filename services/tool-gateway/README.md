# OpsMind Tool Gateway

Separate Spring Boot boundary for policy-controlled, read-only evidence acquisition.

## Local checks

From the repository root:

```powershell
mvn -f services/tool-gateway/pom.xml test
node scripts/validation/validate-phase-06-tool-gateway.mjs
```

The deterministic fixture profile is not a production connector. The default
profile deliberately fails closed because nonce replay, execution receipt,
audit, evidence artifact, and JWKS dependencies are not configured.

## Boundary contract

- `POST /internal/v1/tools/execute` accepts only the canonical Tool Gateway
  request schema and a dedicated workload bearer plus delegated capability.
- Capability `token_use=delegated_capability` is one-use, RS256/JWKS, and binds
  the exact composite action, resource, actor, tenant, run, budgets, and expiry.
- Workload `token_use=workload` uses a different audience and cannot be replaced
  by a delegated capability.
- Manifests are loaded from `src/main/resources/tool-manifests/`; generic shell,
  URL, filesystem, SQL, and admin execution are not supported.
- `/health` is liveness. `/ready` is `503` until durable stores, JWKS, and an
  enabled connector are available.

Never place provider keys, bearer tokens, or personal data in this directory,
fixtures, logs, or documentation. Inject secrets through the deployment secret
manager only.
