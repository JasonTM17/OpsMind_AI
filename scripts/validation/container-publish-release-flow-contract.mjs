import { validateAtomicRelease } from "./container-publish-atomic-release-contract.mjs";
import { requirePromotionOrder } from "./container-publish-promotion-order-contract.mjs";

function findStep(job, name) {
  return job?.steps?.find((step) => step.name === name);
}

function requireStep(jobs, name, errors) {
  const step = Object.values(jobs).find((job) => findStep(job, name));
  if (!step) errors.push(`step.missing:${name}`);
}

function occurrenceCount(source, token) {
  return source.split(token).length - 1;
}

export function validateReleaseFlow(jobs, errors) {
  const expectedSignerWorkflow =
    "${{ github.repository }}/.github/workflows/container-publish.yml";
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
    "Require atomic dual-registry release",
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

  const dualRegistry =
    findStep(jobs.authorize, "Require atomic dual-registry release") ?? {};
  if (
    dualRegistry.env?.PUBLISH_DOCKERHUB !==
      "${{ inputs.publish_dockerhub }}" ||
    !dualRegistry.run?.includes(
      'if [[ "$PUBLISH_DOCKERHUB" != "true" ]]; then',
    ) ||
    !dualRegistry.run?.includes(
      "A releasable version requires coordinated publication to both GHCR and Docker Hub.",
    ) ||
    !dualRegistry.run?.includes("exit 1")
  ) {
    errors.push("release.dual-registry-required");
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
  const stageVerifyStep =
    findStep(jobs.promote, "Verify staged immutable release set") ?? {};
  const stageVerify = stageVerifyStep.run ?? "";
  if (
    !stageVerify.includes("gh attestation verify") ||
    !stageVerify.includes('.visibility == "public"') ||
    !stageVerify.includes(".repository.full_name")
  ) {
    errors.push("release.staged-verification");
  }
  if (
    occurrenceCount(stageVerify, "--bundle-from-oci") !== 2 ||
    occurrenceCount(
      stageVerify,
      '--signer-workflow "$SIGNER_WORKFLOW"',
    ) !== 2 ||
    occurrenceCount(stageVerify, '--source-digest "$GITHUB_SHA"') !== 2 ||
    occurrenceCount(stageVerify, '--source-ref "$GITHUB_REF"') !== 2 ||
    occurrenceCount(stageVerify, "--deny-self-hosted-runners") !== 2 ||
    !stageVerify.includes(
      `jq -e 'length > 0' "$dockerhub_attestation_file"`,
    )
  ) {
    errors.push("release.registry-attestation-policy");
  }
  if (
    !stageVerify.includes(
      'anonymous_config="${RUNNER_TEMP}/opsmind-anonymous-registry-',
    ) ||
    !stageVerify.includes('[[ ! -e "$anonymous_config" ]]') ||
    !stageVerify.includes(
      `printf '%s\\n' '{}' > "$anonymous_config/config.json"`,
    ) ||
    !stageVerify.includes('chmod 600 "$anonymous_config/config.json"') ||
    !stageVerify.includes("unset REGISTRY_AUTH_FILE") ||
    !stageVerify.includes('export DOCKER_CONFIG="$anonymous_config"') ||
    !stageVerify.includes("trap 'rm -rf -- \"$anonymous_config\"' EXIT") ||
    stageVerify.includes("docker login")
  ) {
    errors.push("release.anonymous-registry-verification");
  }
  const receipt =
    findStep(jobs.promote, "Write observed immutable release receipt")?.run ??
    "";
  if (
    !receipt.includes("opsmind-oci-publication-v4") ||
    !receipt.includes("atomicMarkerTag") ||
    receipt.includes("MAJOR_MINOR") ||
    receipt.includes(" latest")
  ) {
    errors.push("release.immutable-receipt");
  }
  if (
    occurrenceCount(receipt, ".dockerHub.published == true") < 2 ||
    occurrenceCount(receipt, ".dockerHub.digest == .digest") < 2 ||
    occurrenceCount(receipt, ".dockerHub.tag == .tag") < 2 ||
    !receipt.includes('registryAccess: "ANONYMOUS"') ||
    !receipt.includes('attestationBundles: "OCI_REGISTRY"') ||
    !receipt.includes("signerWorkflow: $signerWorkflow") ||
    !receipt.includes("sourceDigest: $sourceSha") ||
    !receipt.includes("sourceRef: $sourceRef")
  ) {
    errors.push("release.dual-registry-receipt");
  }
  const aggregateVerifyStep =
    findStep(jobs.promote, "Verify aggregate release evidence attestation") ??
    {};
  const aggregateVerify = aggregateVerifyStep.run ?? "";
  if (
    !aggregateVerify.includes('--signer-workflow "$SIGNER_WORKFLOW"') ||
    !aggregateVerify.includes('--source-digest "$GITHUB_SHA"') ||
    !aggregateVerify.includes('--source-ref "$GITHUB_REF"') ||
    !aggregateVerify.includes("--deny-self-hosted-runners")
  ) {
    errors.push("release.aggregate-attestation-policy");
  }
  if (
    stageVerifyStep.env?.SIGNER_WORKFLOW !== expectedSignerWorkflow ||
    aggregateVerifyStep.env?.SIGNER_WORKFLOW !== expectedSignerWorkflow ||
    !receipt.includes(
      '--arg signerWorkflow "$GITHUB_REPOSITORY/.github/workflows/container-publish.yml"',
    )
  ) {
    errors.push("release.signer-workflow-binding");
  }
  const markerStep = findStep(
    jobs.promote,
    "Publish atomic GitHub release marker",
  );
  validateAtomicRelease(markerStep, errors);
}
