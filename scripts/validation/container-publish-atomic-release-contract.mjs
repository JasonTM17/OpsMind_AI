import fs from "node:fs";

const atomicReleaseScript = fs.readFileSync(
  new URL(
    "../delivery/publish-atomic-github-release.sh",
    import.meta.url,
  ),
  "utf8",
);

export function validateAtomicRelease(markerStep, errors) {
  if (
    markerStep?.run !==
    "bash scripts/delivery/publish-atomic-github-release.sh"
  ) {
    errors.push("release.atomic-marker-command");
  }
  if (
    !atomicReleaseScript.includes("draft: true") ||
    !atomicReleaseScript.includes("tag_name: $tag") ||
    !atomicReleaseScript.includes("target_commitish: $target") ||
    !atomicReleaseScript.includes('make_latest: "false"') ||
    !atomicReleaseScript.includes("release-receipt.json") ||
    !atomicReleaseScript.includes("cleanup_incomplete_marker") ||
    !atomicReleaseScript.includes("tag_owned=true") ||
    !atomicReleaseScript.includes('staged_tag_sha="$(') ||
    !atomicReleaseScript.includes("/git/refs") ||
    !atomicReleaseScript.includes("gh api --method POST") ||
    !atomicReleaseScript.includes("gh release upload") ||
    !atomicReleaseScript.includes("gh api --method PATCH") ||
    !atomicReleaseScript.includes("/releases") ||
    !atomicReleaseScript.includes(".immutable == true")
  ) {
    errors.push("release.atomic-marker");
  }
}
