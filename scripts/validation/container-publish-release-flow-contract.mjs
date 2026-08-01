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
  const expectedSignerDigest = "${{ github.workflow_sha }}";
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
    occurrenceCount(stageVerify, '--signer-digest "$SIGNER_DIGEST"') !== 2 ||
    occurrenceCount(stageVerify, '--source-digest "$GITHUB_SHA"') !== 2 ||
    occurrenceCount(stageVerify, '--source-ref "$GITHUB_REF"') !== 2 ||
    occurrenceCount(stageVerify, "--deny-self-hosted-runners") !== 2 ||
    !stageVerify.includes(
      `jq -e 'length > 0' "$dockerhub_attestation_file"`,
    )
  ) {
    errors.push("release.registry-attestation-policy");
  }
  const anonymousConfigExport = 'export DOCKER_CONFIG="$anonymous_config"';
  const attestationConfigExport = 'export DOCKER_CONFIG="$attestation_config"';
  const anonymousManifestInspection = stageVerify.indexOf(
    'docker buildx imagetools inspect "${ghcr}:${RELEASE_TAG}"',
  );
  const lastManifestInspection = stageVerify.lastIndexOf(
    "docker buildx imagetools inspect",
  );
  const packageInspection = stageVerify.indexOf(
    'gh api "/users/${GITHUB_REPOSITORY_OWNER}/packages/container/${image}"',
  );
  const attestationConfigActivation = stageVerify.indexOf(
    attestationConfigExport,
  );
  const firstAttestation = stageVerify.indexOf("gh attestation verify");
  const ghcrAttestationLogin = stageVerify.indexOf(
    'docker --config "$attestation_config" login ghcr.io',
  );
  const dockerHubAttestationLogin = stageVerify.indexOf(
    'docker --config "$attestation_config" login docker.io',
  );
  const ghcrAttestationCredential = stageVerify.indexOf(
    `printf '%s' "$GHCR_TOKEN"`,
  );
  const dockerHubAttestationCredential = stageVerify.indexOf(
    `printf '%s' "$DOCKERHUB_TOKEN"`,
  );
  if (
    !stageVerify.includes(
      'anonymous_config="${RUNNER_TEMP}/opsmind-anonymous-registry-',
    ) ||
    !stageVerify.includes('[[ ! -e "$anonymous_config" ]]') ||
    !stageVerify.includes(
      `printf '%s\\n' '{}' > "$anonymous_config/config.json"`,
    ) ||
    !stageVerify.includes('chmod 600 "$anonymous_config/config.json"') ||
    !stageVerify.includes(
      'attestation_config="${RUNNER_TEMP}/opsmind-attestation-registry-',
    ) ||
    !stageVerify.includes('[[ ! -e "$attestation_config" ]]') ||
    !stageVerify.includes('mkdir -m 700 "$attestation_config"') ||
    !stageVerify.includes(
      `printf '%s\\n' '{}' > "$attestation_config/config.json"`,
    ) ||
    !stageVerify.includes('chmod 600 "$attestation_config/config.json"') ||
    !stageVerify.includes("unset REGISTRY_AUTH_FILE") ||
    !stageVerify.includes(
      "cleanup_registry_configs() {",
    ) ||
    !stageVerify.includes(
      'docker --config "$attestation_config" logout ghcr.io',
    ) ||
    !stageVerify.includes(
      'docker --config "$attestation_config" logout docker.io',
    ) ||
    !stageVerify.includes('rm -rf -- "$anonymous_config" "$attestation_config"') ||
    !stageVerify.includes("trap cleanup_registry_configs EXIT") ||
    ghcrAttestationLogin < 0 ||
    dockerHubAttestationLogin < 0 ||
    ghcrAttestationCredential < 0 ||
    dockerHubAttestationCredential < 0 ||
    ghcrAttestationCredential > ghcrAttestationLogin ||
    dockerHubAttestationCredential > dockerHubAttestationLogin ||
    !stageVerify
      .slice(ghcrAttestationCredential, anonymousManifestInspection)
      .includes("--password-stdin") ||
    !stageVerify
      .slice(dockerHubAttestationCredential, anonymousManifestInspection)
      .includes("--password-stdin") ||
    occurrenceCount(stageVerify, "export DOCKER_CONFIG=") !== 2 ||
    stageVerify.indexOf(anonymousConfigExport) < 0 ||
    anonymousManifestInspection <= stageVerify.indexOf(anonymousConfigExport) ||
    packageInspection <= anonymousManifestInspection ||
    attestationConfigActivation <= lastManifestInspection ||
    attestationConfigActivation <= packageInspection ||
    firstAttestation <= attestationConfigActivation
  ) {
    errors.push("release.anonymous-registry-verification");
  }
  const receiptStep =
    findStep(jobs.promote, "Write observed immutable release receipt") ?? {};
  const receipt = receiptStep.run ?? "";
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
    !receipt.includes(
      'registryAccess: "ANONYMOUS_MANIFESTS_ISOLATED_AUTHENTICATED_ATTESTATIONS"',
    ) ||
    !receipt.includes('attestationBundles: "OCI_REGISTRY"') ||
    !receipt.includes("signerWorkflow: $signerWorkflow") ||
    !receipt.includes("signerDigest: $signerDigest") ||
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
    !aggregateVerify.includes('--signer-digest "$SIGNER_DIGEST"') ||
    !aggregateVerify.includes('--source-digest "$GITHUB_SHA"') ||
    !aggregateVerify.includes('--source-ref "$GITHUB_REF"') ||
    !aggregateVerify.includes("--deny-self-hosted-runners") ||
    !aggregateVerify.includes('cp -- "$ATTESTATION_BUNDLE"') ||
    !aggregateVerify.includes(
      '"$output_dir/release-evidence-attestation.sigstore.json"',
    ) ||
    !aggregateVerify.includes(
      '--bundle "$output_dir/release-evidence-attestation.sigstore.json"',
    )
  ) {
    errors.push("release.aggregate-attestation-policy");
  }
  if (
    stageVerifyStep.env?.SIGNER_WORKFLOW !== expectedSignerWorkflow ||
    stageVerifyStep.env?.SIGNER_DIGEST !== expectedSignerDigest ||
    aggregateVerifyStep.env?.SIGNER_WORKFLOW !== expectedSignerWorkflow ||
    aggregateVerifyStep.env?.SIGNER_DIGEST !== expectedSignerDigest ||
    !receipt.includes(
      '--arg signerWorkflow "$GITHUB_REPOSITORY/.github/workflows/container-publish.yml"',
    ) ||
    !receipt.includes('--arg signerDigest "$SIGNER_DIGEST"') ||
    receiptStep.env?.SIGNER_DIGEST !== expectedSignerDigest
  ) {
    errors.push("release.signer-workflow-binding");
  }
  const markerStep = findStep(
    jobs.promote,
    "Publish atomic GitHub release marker",
  );
  validateAtomicRelease(markerStep, errors);
}
