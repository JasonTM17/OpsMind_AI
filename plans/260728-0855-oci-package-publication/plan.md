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
GHCR uses the scoped GitHub token. Every releasable version must publish the
same verified digests to both GHCR and Docker Hub through the protected
environment; a single-registry run is not release-authoritative.

## Scope

1. Add a SHA-pinned GitHub Actions workflow for `linux/amd64` and `linux/arm64`.
2. Publish Platform API, AI Runtime, Tool Gateway, and Operator Web to GHCR.
3. Build immutable candidate SHA references, scan/test/sign all four, then
   promote their exact digests through the protected `oci-production`
   environment.
4. Require environment-scoped Docker Hub credentials and reject manual
   publication unless coordinated dual-registry parity is explicitly enabled.
5. Emit one secret-free candidate receipt per component and one aggregate
   release-set receipt.
6. Add a structural YAML validator and negative mutation tests to PR quality.
7. Document operator commands, credential names, evidence, and rollback.

## Acceptance Criteria

- All action dependencies are pinned to immutable commit SHAs.
- Top-level permissions are empty. The authorizer alone adds `actions: read` to
  prove exact-SHA CI; candidates use `contents: read`/`packages: write`, while
  protected promotion adds only `contents: write`, `packages: write`,
  `id-token: write`, `attestations: write`, and `artifact-metadata: write`.
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
  Immutable version tags are staged first; a signed aggregate receipt and exact
  GitHub Release marker activate the release set only after every digest and
  signature passes. Repository-level immutable releases lock the marker/tag.
  Receipt, Sigstore bundle, and evidence archive are draft assets verified
  before publication. No mutable component channel is published.
- Docker Hub promotion fails closed when either environment credential is
  absent or dual-registry publication is not enabled; the token is never
  job-scoped.
- Published registry digests, observed platforms, scan counts, health, package
  visibility/linkage, and signatures are verified before the atomic marker.
  Public manifests use a credential-free configuration; OCI attestation bundles
  use a separately created, short-lived authenticated configuration because the
  GitHub CLI requires registry authentication for OCI bundle reads.
- Component and aggregate attestations bind the signer workflow, immutable
  workflow-file digest, source SHA, and source ref. Aggregate verification uses
  the exact copied Sigstore bundle that becomes the release asset.
- PR quality, Compose build/health, actionlint, secret scanning, and static
  publication validation pass on the exact source revision.

## Rollback

Disable the workflow trigger or revoke `packages: write`; do not delete package
versions until their digest references and release receipts are inventoried.
Never repoint an immutable component tag or existing release marker. Publish a
new reviewed SemVer release whose signed receipt references the selected
already-verified digests. Production promotion never consumes a tag without
resolving and recording its digest.

## External Dependency

Docker Hub publication cannot execute until environment variable
`DOCKERHUB_USERNAME` and environment secret `DOCKERHUB_TOKEN` exist in
`oci-production`. Neither value is inferred, generated, or committed. Initial
candidate builds may create SHA-scoped GHCR package records before protected
promotion stops at its preflight. Those packages must be public and linked to
this repository, immutable releases must remain enabled, and the same exact
version/SHA run must then be retried with dual-registry publication enabled.
