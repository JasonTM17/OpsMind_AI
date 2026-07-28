export function requirePromotionOrder(job, errors) {
  const names = job?.steps?.map((step) => step.name) ?? [];
  const required = [
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
  ];
  const positions = required.map((name) => names.indexOf(name));
  const ordered =
    positions.every((position) => position >= 0) &&
    positions.every(
      (position, index) => index === 0 || position > positions[index - 1],
    );
  const componentAttestPositions = names
    .map((name, index) =>
      name?.includes(" on GHCR") || name?.includes(" on Docker Hub") ? index : -1,
    )
    .filter((index) => index >= 0);
  const promotePosition = names.indexOf("Promote tested digests");
  const closePosition = names.indexOf("Close registry credential session");
  const verifyPosition = names.indexOf("Verify staged immutable release set");
  const attestationsOrdered =
    componentAttestPositions.length === 8 &&
    componentAttestPositions.every(
      (position) => position > promotePosition && position < closePosition,
    );
  const componentAttestationsConfigured = componentAttestPositions.every(
    (position) =>
      job.steps[position]?.with?.["push-to-registry"] === true &&
      job.steps[position]?.with?.["create-storage-record"] === false,
  );
  if (!componentAttestationsConfigured) {
    errors.push("release.component-attestation-config");
  }
  if (
    !ordered ||
    !attestationsOrdered ||
    closePosition <= promotePosition ||
    closePosition >= verifyPosition
  ) {
    errors.push("release.step-order");
  }
}
