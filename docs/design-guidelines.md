# OpsMind AI Design Guidelines

## Purpose

OpsMind is an incident-work surface for operators under time pressure. Clarity,
provenance, safe degraded behavior, and keyboard speed outrank decoration.

## Visual Direction

Use a forensic instrument-panel language:

- asymmetric Swiss grid rather than a wall of equal cards;
- graphite-paper surfaces rather than pure black;
- warm off-white primary text and desaturated steel secondary text;
- one signal-amber accent for focus and attention;
- red only for explicit failure, always paired with a word and shape;
- fine dividers and spacing for grouping before shadows or containers;
- restrained radii: 4 pixels for inner controls, 8 pixels for major surfaces.

Do not use neon glow, purple/blue gradients, glassmorphism, generic bento
layouts, oversized centered heroes, or decorative real-time charts.

## Typography

- UI and body: `Source Sans 3`, `Geist`, or a metrically compatible system
  humanist sans fallback.
- Identifiers, timestamps, digests, budgets, and measurements: `IBM Plex Mono`
  or a compatible system monospace.
- Body text is at least 16 pixels on narrow screens.
- Metadata may be 12–14 pixels only when its contrast and line height remain
  readable.
- Numeric columns use tabular figures.
- Sentence case is the default. All-caps is reserved for short environment and
  severity labels.

## Color Tokens

Components consume semantic tokens, never ad hoc raw colors:

| Token | Meaning |
|---|---|
| `surface-canvas` | application background |
| `surface-panel` | primary working surface |
| `surface-raised` | selected or expanded content |
| `text-primary` | critical readable content |
| `text-secondary` | supporting metadata |
| `border-subtle` | layout grouping |
| `accent-signal` | focus, current step, primary read action |
| `status-danger` | failed or unsafe state |
| `status-stable` | verified safe state |

All normal text meets WCAG AA 4.5:1. Large text and non-text controls meet at
least 3:1. Status never relies on color alone.

## Evidence Spine

The Evidence Spine is the signature investigation pattern. It orders only
facts present in an authorized backend projection:

1. run start and bounded policy;
2. accepted analysis rounds;
3. catalog-selected read-only tool intents;
4. durable evidence references and digests;
5. cited terminal analysis or explicit stop reason.

Each available step includes a timestamp, text status, stable identifier, and
provenance. Missing fields remain visibly unavailable. The UI never fabricates
service timings, query text, trace hops, or evidence content.

Operator Web consumes the versioned
`application/vnd.opsmind.operator-projection.v1+json` representation. Platform
must emit the projection class, redaction policy version, and decimal
redaction-leaf count in response headers; the browser rejects a response that
does not carry all three assurances. Model-authored prose, rationale, claims,
counter-evidence, and missing-evidence text are replaced by controlled display
labels unless the Platform projection explicitly proves them safe.

## Interaction and States

- Every interactive target is at least 44 by 44 CSS pixels.
- Keyboard focus is visible and follows the visual reading order.
- Loading longer than 300 milliseconds uses a layout-shaped skeleton.
- Empty, stale, denied, unavailable, abstained, budget-exceeded, no-progress,
  and failed states explain what remains safe and the next read-only action.
- Motion uses only transform or opacity, normally 150–240 milliseconds.
- `prefers-reduced-motion` disables non-essential transitions.
- Copy controls have text labels and announce completion without stealing focus.
- No action calls Tool Gateway, AI Runtime, or Prometheus from the browser.
- Read-only incident and investigation loads use `incident:read` and a
  project/incident/run-scoped server query. A client cannot widen the scope by
  changing a path identifier.

## Responsive Rules

- Desktop uses an asymmetric context / evidence / conclusion grid.
- Tablet moves the conclusion below the Evidence Spine.
- At 767 pixels and below, content becomes one column with the incident summary
  first.
- At 375 pixels there is no page-level horizontal overflow.
- Long IDs wrap safely and retain a labeled copy control.
- Avoid nested scrolling regions; the document remains the primary scroll.

## Security and Content

Browser output may include authorized read models and citation metadata. It must
not include credentials, bearer tokens, refresh tokens, raw prompts, provider
reasoning, unrestricted URLs, arbitrary PromQL, private labels, or unredacted
evidence payloads.

Plain, specific operational language is required. Avoid marketing claims,
emoji as icons, placeholder people or companies, and invented round-number
telemetry.

## Verification

Every operator surface must pass:

- unit and contract-boundary tests;
- lint, TypeScript, and production build;
- Chromium browser flow at desktop and 375-pixel viewport;
- keyboard focus and reduced-motion checks;
- automated accessibility scan or equivalent assertions;
- negative assertions for secrets, tokens, prompts, reasoning, and executable
  query text.
- operator-projection content negotiation and assurance-header checks,
  including malformed or unacceptable `Accept` values.

## Unresolved Questions

- Production enterprise IdP and server-owned BFF session proof remains a later
  release gate.
- Rich investigation event/evidence projection is not yet a public frontend
  contract.
