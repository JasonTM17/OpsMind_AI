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
3. Require Docker Hub credentials for release-tag parity; allow a GHCR-only
   manual bootstrap while those external credentials are absent.
4. Emit and upload one secret-free publication receipt per component.
5. Add a static validator to the PR quality gate.
6. Document operator commands, credential names, evidence, and rollback.

## Acceptance Criteria

- All action dependencies are pinned to immutable commit SHAs.
- Workflow permissions are limited to `contents: read` and `packages: write`.
- Four images build from their existing Dockerfiles without build-time secrets.
- Each pushed image has `mode=max` provenance and an attached SBOM.
- GHCR package labels link to this repository and expose source revision.
- Release tags fail closed when Docker Hub publication is requested but either
  `DOCKERHUB_USERNAME` or `DOCKERHUB_TOKEN` is absent.
- Published registry digests are verified and written to CI receipts.
- PR quality, Compose build/health, actionlint, secret scanning, and static
  publication validation pass on the exact source revision.

## Rollback

Disable the workflow trigger or revoke `packages: write`; do not delete package
versions until their digest references and release receipts are inventoried.
Mutable tags may be repointed only to an already verified digest. Production
promotion never consumes a tag without resolving and recording its digest.

## External Dependency

Docker Hub publication cannot execute until repository variable
`DOCKERHUB_USERNAME` and protected secret `DOCKERHUB_TOKEN` exist. Neither value
is inferred, generated, or committed by this plan.
