# OpsMind Operator Web

Next.js App Router console where an operator reads an investigation. It renders a
deliberately narrow projection of what the Platform API already authorized, and
holds no credential of its own.

## Local checks

From this directory:

```powershell
pnpm lint
pnpm typecheck
pnpm test
pnpm test:e2e
```

`pnpm test:e2e` needs browsers in the repository cache. Install them once with
`PLAYWRIGHT_BROWSERS_PATH` pointing at `.opsmind/cache/playwright`, which the
repository launchers set for you:

```powershell
pnpm --filter @opsmind/operator-web exec playwright install chromium
```

`pnpm test:e2e:production` runs the same specs against a production build and is
the configuration CI uses for the fail-closed suite.

## Routes

- `/` is the operator entry. It lists nothing and exposes no fixture selector,
  because an unauthenticated visitor must not learn which runs exist.
- `/organizations/[organizationId]/projects/[projectId]/incidents/[incidentId]/investigations/[runId]`
  renders one investigation. Every segment is part of the authorization scope,
  not a convenience for building links.

The route is `force-dynamic`. There is no cached or statically rendered view of
tenant data.

## What this app does not do

- It holds no session cookie, bearer token, or provider credential. The browser
  storage assertions in `tests/e2e/` fail the suite if one appears.
- It never retries a failed platform read. A bounded dependency failure renders
  an explicit unavailable state carrying a support correlation identifier, and
  the last durable state is left unchanged.
- It renders no model-authored prose that the Platform API has not already
  accepted and classified. A projection carrying raw reasoning fields is
  rejected before render rather than filtered during it.

`lib/platform-api/load-investigation-workspace.ts` converts every failure —
transport, contract, or identity mismatch — into a typed unavailable state, so a
page cannot throw its way to a blank screen.

## Accessibility

Every rendered state is scanned against WCAG 2.1 AA with `@axe-core/playwright`,
including the entry route and each investigation terminal state. Keyboard skip
navigation, focus order, reduced motion, and a 375-pixel viewport are asserted
as behaviour, not as configuration.
