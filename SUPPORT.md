# Support

## Before asking for help

Check the [README](README.md), [local development guide](docs/local-development.md), [deployment guide](docs/deployment-guide.md), [testing strategy](docs/testing-strategy.md), and [known blockers](docs/blockers.md). Run the `doctor` command and include its sanitized output:

```powershell
.\scripts\dev\opsmind.ps1 doctor
```

## Opening a question

Use a GitHub Discussion for usage questions, architecture discussion, and design proposals. Open an issue only for a reproducible defect or a scoped feature request. Include the revision/image digest, exact command, expected result, observed result, and sanitized evidence.

Never include API keys, tokens, passwords, private keys, customer data, raw sensitive prompts, or production identifiers. Security vulnerabilities belong in the private advisory flow described in [SECURITY.md](SECURITY.md).

## Project status

OpsMind AI is under active development. Phase 4 checkpoint 4A has local/reference evidence; provider, evidence-object, UI, remediation, production identity, and final package-release gates remain in progress. See [the roadmap](docs/project-roadmap.md) for the authoritative status.
