# Repository media

These assets are captured from the Operator Web E2E fixture stack. They are
product evidence, not marketing mockups: the browser receives the same narrow
operator projection exercised by the accessibility and contract tests.

| Asset | What it proves |
| --- | --- |
| `operator-investigation-workspace.png` | Desktop reading path: incident context, evidence spine, cited conclusion |
| `operator-investigation-workspace-walkthrough.gif` | Bounded visual walkthrough of the desktop reading path from incident context through cited evidence |

## Regenerate

From the repository root, with the Operator Web dependencies and Playwright
browser cache installed:

```powershell
node scripts/media/capture-operator-media.mjs
powershell -File scripts/governance/scan-project-secrets.ps1
```

The script starts the same E2E fixture stack used by the browser tests, captures
the completed desktop projection, derives the GIF from that reviewed
screenshot, and rewrites the exact digest/size/dimension fields in
`media-manifest.json`.

## Review rules

- Do not hand-edit screenshots or add data that is not present in the fixture
  projection.
- Never include credentials, raw prompts, provider reasoning, executable query
  text, customer data, or unredacted evidence payloads.
- If the operator projection changes, regenerate and review all assets before
  updating the README.
- The manifest is required because the repository scanner fails closed for
  unlisted or changed binary files.

## Known limits

The assets demonstrate the operator read surface. They do not prove a live
provider call, production identity, a live connector, or production deployment
readiness. Those claims remain governed by the gates listed in the root README
and `docs/blockers.md`.
