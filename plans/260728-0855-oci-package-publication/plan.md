---
title: OCI package publication
status: in-progress
owner: platform-team
date: 2026-07-28
---

# OCI Package Publication

## Goal

Publish the four production runtime images as immutable multi-architecture OCI
packages with repository linkage, SBOM, provenance, and revision-bound receipts.
GHCR uses the scoped GitHub token. Docker Hub publication is enabled only when
the protected username/token pair is configured.

## Scope

1. Add a SHA-pinned GitHub Actions workflow for `linux/amd64` and `linux/arm64`.
2. Publish Platform API, AI Runtime, Tool Gateway, and Operator Web to GHCR.
3. Build immutable candidate SHA references, scan/test/sign all four, then
   promote their exact digests through the protected `oci-production`
   environment.
4. Require environment-scoped Docker Hub credentials for optional registry
   parity while allowing a GHCR-only manual bootstrap.
5. Emit one secret-free candidate receipt per component and one aggregate
   release-set receipt.
6. Add a structural YAML validator and negative mutation tests to PR quality.
7. Document operator commands, credential names, evidence, and rollback.

## Acceptance Criteria

- All action dependencies are pinned to immutable commit SHAs.
- Top-level permissions are empty. The authorizer alone adds `actions: read` to
  prove exact-SHA CI; candidate and promotion jobs use only `contents: read`,
  `packages: write`, `id-token: write`, and `attestations: write` where needed.
- Four images build from their existing Dockerfiles without build-time secrets.
- Each candidate has `mode=max` provenance, an attached SBOM, zero fixable
  HIGH/CRITICAL vulnerabilities or detected secrets, an observed license
  inventory, and a passing runtime health probe on both `linux/amd64` and
  `linux/arm64`.
- GHCR package labels link to this repository and expose source revision.
- Manual publication is limited to `main`, requires strict SemVer, and proves
  both quality workflows passed on the exact source SHA.
- Promotion requires all four candidate receipts, a protected environment
  approval, public repository-linked GHCR packages, and verified attestations.
  Immutable version tags are staged first; `latest` activates only after every
  digest and signature passes.
- Docker Hub promotion fails closed when either environment credential is
  absent; the token is never job-scoped.
- Published registry digests, observed platforms, scan counts, health, package
  visibility/linkage, and signatures are verified before `latest` activation.
- PR quality, Compose build/health, actionlint, secret scanning, and static
  publication validation pass on the exact source revision.

## Rollback

Disable the workflow trigger or revoke `packages: write`; do not delete package
versions until their digest references and release receipts are inventoried.
Mutable tags may be repointed only to an already verified digest. Production
promotion never consumes a tag without resolving and recording its digest.

## External Dependency

Docker Hub publication cannot execute until environment variable
`DOCKERHUB_USERNAME` and environment secret `DOCKERHUB_TOKEN` exist in
`oci-production`. Neither value is inferred, generated, or committed. Initial
GHCR promotion also waits until the candidate packages are public and linked to
this repository.
