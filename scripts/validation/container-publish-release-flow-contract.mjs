function findStep(job, name) {
  return job?.steps?.find((step) => step.name === name);
}

export function validateReleaseFlow(jobs, errors) {
  const candidateJob = jobs["build-candidate"];
  const build = findStep(
    candidateJob,
    "Build immutable multi-architecture candidate",
  );
  if (
    build?.with?.platforms !== "linux/amd64,linux/arm64" ||
    build?.with?.push !== true ||
    build?.with?.provenance !== "mode=max" ||
    build?.with?.sbom !== true ||
    build?.with?.tags !== "${{ steps.target.outputs.candidate_ref }}"
  ) {
    errors.push("candidate build contract is incomplete or publishes public tags");
  }

  const scan = findStep(candidateJob, "Scan candidate image");
  if (
    scan?.uses !==
      "aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25" ||
    scan?.with?.scanners !== "vuln,secret,license"
  ) {
    errors.push("candidate image scan must cover vulnerability, secret, and license data");
  }

  const contracts = [
    [
      findStep(
        candidateJob,
        "Verify manifest, labels, scan policy, and runtime health",
      )?.run,
      ["predicate-type", "high_critical", "docker run"],
      "candidate verification",
    ],
    [
      findStep(jobs.promote, "Verify aggregate candidate release set")?.run,
      ['visibility == "public"', "gh attestation verify"],
      "aggregate gate",
    ],
    [
      findStep(jobs.promote, "Promote immutable version and revision tags")?.run,
      ["imagetools create", "verify_tag"],
      "digest promotion",
    ],
    [
      findStep(
        jobs.promote,
        "Verify signatures, activate latest, and write release-set receipt",
      )?.run,
      ["gh attestation verify", ":latest", "release-set.json"],
      "release activation",
    ],
  ];
  for (const [run, tokens, description] of contracts) {
    if (!tokens.every((token) => run?.includes(token))) {
      errors.push(`${description} step is structurally incomplete`);
    }
  }
}
