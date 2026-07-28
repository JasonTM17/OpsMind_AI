function findStep(job, name) {
  return job?.steps?.find((step) => step.name === name);
}

function requireStep(jobs, name, errors) {
  const step = Object.values(jobs).find((job) => findStep(job, name));
  if (!step) errors.push(`step.missing:${name}`);
}

export function validateReleaseFlow(jobs, errors) {
  const candidate = jobs["build-candidate"];
  const metadata = findStep(candidate, "Derive immutable candidate metadata");
  const build = findStep(candidate, "Build immutable candidate");

  if (
    metadata?.with?.tags !==
      "type=raw,value=candidate-${{ github.sha }}-${{ github.run_attempt }}\n" ||
    build?.with?.tags !== "${{ steps.meta.outputs.tags }}" ||
    build?.with?.platforms !== "linux/amd64,linux/arm64" ||
    build?.with?.push !== true
  ) {
    errors.push("candidate.immutable-push");
  }
  if (build?.with?.provenance !== "mode=max" || build?.with?.sbom !== true) {
    errors.push("candidate.attestations");
  }

  for (const name of [
    "Verify exact-revision quality gates",
    "Inspect candidate evidence",
    "Smoke-test candidate runtime",
    "Scan amd64 vulnerabilities and secrets",
    "Scan arm64 vulnerabilities, secrets, and both license inventories",
    "Validate aggregate candidate release set",
    "Promote tested digests",
    "Verify staged digests before mutable tag activation",
    "Activate mutable release tags",
    "Verify promoted release set and write observed receipt",
  ]) {
    requireStep(jobs, name, errors);
  }

  const inspect = findStep(candidate, "Inspect candidate evidence")?.run ?? "";
  if (
    ![
      ".Manifest",
      ".SBOM",
      ".Provenance",
      "linux/amd64",
      "linux/arm64",
      "org.opencontainers.image.source",
      "org.opencontainers.image.revision",
    ].every((token) => inspect.includes(token))
  ) {
    errors.push("candidate.observed-evidence");
  }

  const smoke = findStep(candidate, "Smoke-test candidate runtime")?.run ?? "";
  if (
    !smoke.includes("for architecture in amd64 arm64") ||
    !smoke.includes('--platform "linux/${architecture}"')
  ) {
    errors.push("candidate.dual-platform-smoke");
  }

  const amd64Scan = findStep(
    candidate,
    "Scan amd64 vulnerabilities and secrets",
  );
  const arm64Scan =
    findStep(
      candidate,
      "Scan arm64 vulnerabilities, secrets, and both license inventories",
    )?.run ?? "";
  if (
    amd64Scan?.env?.TRIVY_PLATFORM !== "linux/amd64" ||
    amd64Scan?.with?.scanners !== "vuln,secret" ||
    !arm64Scan.includes("--platform linux/arm64") ||
    !arm64Scan.includes("--scanners license")
  ) {
    errors.push("candidate.dual-platform-scan");
  }

  const promote = findStep(jobs.promote, "Promote tested digests")?.run ?? "";
  if (
    !promote.includes("oras cp --recursive") ||
    promote.includes(",latest") ||
    promote.includes(":latest")
  ) {
    errors.push("release.public-tag-order");
  }
  const stageVerify =
    findStep(
      jobs.promote,
      "Verify staged digests before mutable tag activation",
    )?.run ?? "";
  if (
    !stageVerify.includes("gh attestation verify") ||
    !stageVerify.includes('.visibility == "public"') ||
    !stageVerify.includes(".repository.full_name")
  ) {
    errors.push("release.staged-verification");
  }
  const activate =
    findStep(jobs.promote, "Activate mutable release tags")?.run ?? "";
  if (
    !activate.includes(",latest") ||
    !activate.includes("oras cp --recursive") ||
    !activate.includes('IS_PRERELEASE" == "true')
  ) {
    errors.push("release.mutable-activation");
  }
}
