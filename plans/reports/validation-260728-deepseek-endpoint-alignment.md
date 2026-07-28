# Validation Report — DeepSeek V4 Flash Endpoint Alignment

Date: 2026-07-28

## Scope

Align the checked-in DeepSeek provider endpoint with the official OpenAI-compatible
origin while preserving the fail-closed default and provider trust boundary.

## Changes

- Added `DEFAULT_DEEPSEEK_API_BASE_URL` to the typed AI Runtime settings.
- Changed `.env.example` and the AI Runtime Compose default to
  `https://api.deepseek.com`.
- Synchronized `services/ai-runtime/.env.example` and added a parity test so
  both operator entry points match the typed default.
- Added a settings regression assertion for the default endpoint.
- Documented the endpoint and the fact that egress remains disabled by default.

The patch does not add, read, persist, or transmit an API key.

## Evidence

The official DeepSeek documentation lists `deepseek-v4-flash` and
`https://api.deepseek.com` as the model and OpenAI-format base URL:
<https://api-docs.deepseek.com/quick_start/pricing/>.

Commands run from the dedicated worktree:

```text
python -m pytest services/ai-runtime/tests/unit/test_settings.py -q
26 passed

python -m pytest services/ai-runtime/tests -q
168 passed, 5 skipped

python -m ruff check services/ai-runtime/src services/ai-runtime/tests
All checks passed!

python -m mypy services/ai-runtime/src
Success: no issues found in 36 source files

docker compose config --quiet
pass
```

## Release interpretation

This closes endpoint-default correctness only. It does not close B-004:
provider processing terms, region, retention behavior, redaction approval, an
externally injected rotated staging key, and immutable live/synthetic
conformance evidence remain required before enabling provider egress.
