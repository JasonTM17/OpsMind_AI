#!/usr/bin/env bash

set -euo pipefail

output_dir="artifacts/container-publication"
receipt="$output_dir/release-receipt.json"
github_tag="v${RELEASE_VERSION}"
receipt_sha256="$(sha256sum "$receipt" | cut -d ' ' -f 1)"
release_body="$(
  jq -r \
    --arg receiptSha256 "$receipt_sha256" \
    '"Atomic OpsMind AI release set for source `" + .sourceSha + "`.\n\n" +
     "Signed receipt SHA-256: `" + $receiptSha256 + "`\n\n" +
     (.components
       | map("- `" + .image + ":" + .tag + "` → `" + .digest + "`")
       | join("\n")) +
     "\n\nThe Git tag and this release are the authoritative activation marker. " +
     "Per-image tags are immutable staging references."' \
    "$receipt"
)"
jq -n \
  --arg tag "$github_tag" \
  --arg target "$GITHUB_SHA" \
  --arg name "OpsMind AI ${RELEASE_VERSION}" \
  --arg body "$release_body" \
  --argjson prerelease "$IS_PRERELEASE" \
  '{
    tag_name: $tag,
    target_commitish: $target,
    name: $name,
    body: $body,
    draft: true,
    prerelease: $prerelease,
    generate_release_notes: false,
    make_latest: "false"
  }' > "$output_dir/github-release-draft-request.json"

release_id=
tag_owned=false
completed=false

cleanup_incomplete_marker() {
  if [[ "$completed" == "true" ]]; then
    return
  fi
  if [[ -n "$release_id" ]]; then
    release_state="$(
      gh api \
        "repos/${GITHUB_REPOSITORY}/releases/${release_id}" \
        2>/dev/null
    )" || return
    if jq -e '.draft == false' <<<"$release_state" >/dev/null; then
      return
    fi
    if ! jq -e '.draft == true' <<<"$release_state" >/dev/null; then
      return
    fi
    gh api --method DELETE \
      "repos/${GITHUB_REPOSITORY}/releases/${release_id}" \
      >/dev/null 2>&1 || return
  fi
  if [[ "$tag_owned" == "true" ]]; then
    tag_sha="$(
      gh api \
        "repos/${GITHUB_REPOSITORY}/git/ref/tags/${github_tag}" \
        --jq '.object.sha' 2>/dev/null
    )" || return
    if [[ "$tag_sha" == "$GITHUB_SHA" ]]; then
      gh api --method DELETE \
        "repos/${GITHUB_REPOSITORY}/git/refs/tags/${github_tag}" \
        >/dev/null 2>&1 || true
    fi
  fi
}
trap cleanup_incomplete_marker EXIT

jq -n \
  --arg ref "refs/tags/${github_tag}" \
  --arg sha "$GITHUB_SHA" \
  '{ref: $ref, sha: $sha}' \
  > "$output_dir/github-release-tag-request.json"
gh api --method POST \
  "repos/${GITHUB_REPOSITORY}/git/refs" \
  --input "$output_dir/github-release-tag-request.json" \
  > "$output_dir/github-release-tag.json"
tag_owned=true
jq -e \
  --arg ref "refs/tags/${github_tag}" \
  --arg sha "$GITHUB_SHA" \
  '.ref == $ref and .object.sha == $sha' \
  "$output_dir/github-release-tag.json" >/dev/null

gh api --method POST \
  "repos/${GITHUB_REPOSITORY}/releases" \
  --input "$output_dir/github-release-draft-request.json" \
  > "$output_dir/github-release-draft.json"
release_id="$(
  jq -r '.id | select(type == "number")' \
    "$output_dir/github-release-draft.json"
)"
[[ -n "$release_id" ]]

for asset in \
  "$output_dir/release-receipt.json" \
  "$output_dir/publication-evidence.tar.gz" \
  "$output_dir/release-evidence-attestation.sigstore.json"; do
  gh release upload "$github_tag" "$asset" \
    --repo "$GITHUB_REPOSITORY"
done
gh api \
  "repos/${GITHUB_REPOSITORY}/releases/${release_id}" \
  > "$output_dir/github-release-staged.json"
jq -e \
  --arg receiptDigest "sha256:$receipt_sha256" \
  '.draft == true
    and (.assets | length) == 3
    and (
      [.assets[].name] | sort
    ) == [
      "publication-evidence.tar.gz",
      "release-evidence-attestation.sigstore.json",
      "release-receipt.json"
    ]
    and any(.assets[];
      .name == "release-receipt.json"
      and .digest == $receiptDigest
    )
    and all(.assets[];
      (.digest | test("^sha256:[0-9a-f]{64}$"))
    )' \
  "$output_dir/github-release-staged.json" >/dev/null

staged_tag_sha="$(
  gh api \
    "repos/${GITHUB_REPOSITORY}/git/ref/tags/${github_tag}" \
    --jq '.object.sha'
)"
[[ "$staged_tag_sha" == "$GITHUB_SHA" ]]
printf '%s\n' \
  '{"draft":false,"make_latest":"false"}' \
  > "$output_dir/github-release-publish-request.json"
if ! gh api --method PATCH \
    "repos/${GITHUB_REPOSITORY}/releases/${release_id}" \
    --input "$output_dir/github-release-publish-request.json" \
    > "$output_dir/github-release.json"; then
  publication_observed=false
  for attempt in {1..10}; do
    if gh api \
        "repos/${GITHUB_REPOSITORY}/releases/${release_id}" \
        > "$output_dir/github-release.json" \
      && jq -e \
        '.draft == false and .immutable == true' \
        "$output_dir/github-release.json" >/dev/null; then
      publication_observed=true
      break
    fi
    echo "Published release state not observable (attempt ${attempt}/10)."
    sleep 2
  done
  [[ "$publication_observed" == "true" ]]
fi
jq -e \
  --arg tag "$github_tag" \
  --argjson prerelease "$IS_PRERELEASE" \
  '.tag_name == $tag
    and .draft == false
    and .prerelease == $prerelease
    and .immutable == true
    and (.html_url | startswith("https://github.com/"))' \
  "$output_dir/github-release.json" >/dev/null
completed=true
trap - EXIT
