import { validateAtomicRelease } from "./container-publish-atomic-release-contract.mjs";
import { requirePromotionOrder } from "./container-publish-promotion-order-contract.mjs";

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
      "type=raw,value=candidate-${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}\n" ||
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
    "Prepare registry credential session",
    "Log in to GHCR for promotion and attestation",
    "Log in to Docker Hub for promotion and attestation",
    "Promote tested digests",
    "Close registry credential session",
    "Verify staged immutable release set",
    "Write observed immutable release receipt",
    "Attest aggregate release evidence",
    "Verify aggregate release evidence attestation",
    "Upload publication evidence to workflow run",
    "Publish atomic GitHub release marker",
  ]) {
    requireStep(jobs, name, errors);
  }
  requirePromotionOrder(jobs.promote, errors);

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
    !smoke.includes('--platform "linux/${architecture}"') ||
    !smoke.includes("--connect-timeout 2 --max-time 5")
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
  const aggregate =
    findStep(
      jobs.promote,
      "Validate aggregate candidate release set",
    )?.run ?? "";
  if (
    !aggregate.includes("/immutable-releases") ||
    !aggregate.includes(".enabled == true") ||
    !aggregate.includes("releases?per_page=100") ||
    !aggregate.includes(".data.repository.ref == null")
  ) {
    errors.push("release.immutability-preflight");
  }
  const openCredentials =
    findStep(jobs.promote, "Prepare registry credential session") ?? {};
  const closeCredentials =
    findStep(jobs.promote, "Close registry credential session") ?? {};
  const ghcrLogin =
    findStep(
      jobs.promote,
      "Log in to GHCR for promotion and attestation",
    ) ?? {};
  const dockerHubLogin =
    findStep(
      jobs.promote,
      "Log in to Docker Hub for promotion and attestation",
    ) ?? {};
  if (
    !openCredentials.run?.includes('config_file="${config_dir}/config.json"') ||
    !openCredentials.run?.includes("getent passwd") ||
    !closeCredentials.run?.includes("docker logout") ||
    !closeCredentials.run?.includes('mv -- "$backup_file" "$config_file"') ||
    !String(closeCredentials.if).includes("always()")
  ) {
    errors.push("release.credential-lifecycle");
  }
  if (
    ghcrLogin.uses !==
      "docker/login-action@abd2ef45e78c5afb21d64d4ca52ee8550d9572c7" ||
    ghcrLogin.with?.registry !== "ghcr.io" ||
    dockerHubLogin.uses !==
      "docker/login-action@abd2ef45e78c5afb21d64d4ca52ee8550d9572c7" ||
    dockerHubLogin.with?.registry !== "docker.io" ||
    !String(dockerHubLogin.if).includes("dockerhub_enabled")
  ) {
    errors.push("release.registry-logins");
  }
  const stageVerify =
    findStep(
      jobs.promote,
      "Verify staged immutable release set",
    )?.run ?? "";
  if (
    !stageVerify.includes("gh attestation verify") ||
    !stageVerify.includes('.visibility == "public"') ||
    !stageVerify.includes(".repository.full_name")
  ) {
    errors.push("release.staged-verification");
  }
  const receipt =
    findStep(jobs.promote, "Write observed immutable release receipt")?.run ??
    "";
  if (
    !receipt.includes("opsmind-oci-publication-v3") ||
    !receipt.includes("atomicMarkerTag") ||
    receipt.includes("MAJOR_MINOR") ||
    receipt.includes(" latest")
  ) {
    errors.push("release.immutable-receipt");
  }
  const markerStep = findStep(
    jobs.promote,
    "Publish atomic GitHub release marker",
  );
  validateAtomicRelease(markerStep, errors);
}
