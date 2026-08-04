---
title: Repository documentation consolidation
description: >-
  Rewrite the repository documentation around the merged main branch, expose a
  clear operator/developer reading path, and retire stale branch references.
status: completed
priority: P1
branch: main
tags:
  - docs
  - repository
  - release
  - ck-workflow
created: '2026-08-04'
createdBy: 'ck:plan'
source: skill
---

# Repository documentation consolidation

## Overview

Refresh the root README and canonical `docs/` set from the current `main`
checkout after PR #70 merged. Documentation must describe verified behavior,
current delivery gates, safe local commands, and explicit production limits.
Stale branch/worktree references are reconciled without deleting unrelated
local work or protected storage.

## Phases

| Phase | Name | Status |
|---|---|---|
| 1 | Repository and branch audit | Completed |
| 2 | Documentation rewrite and navigation | Completed |
| 3 | Validation and consistency review | Completed |
| 4 | Main-branch and worktree consolidation | Completed |

## Scope

- In: `README.md`, evergreen docs under `docs/`, this plan, stale merged-branch
  metadata, documentation validation, and focused repository checks.
- Out: application behavior, unrelated dirty files, Docker volumes, protected
  worktrees, and production deployment.

## Acceptance criteria

- README provides a concise project entry point, quick start, architecture,
  verification commands, current status, blockers, and doc navigation.
- Canonical docs agree with code and merged `main` at `0788ee3`.
- Internal links and documented commands are validated; warnings are either
  fixed or explicitly recorded as heuristic limitations.
- The stale artifact runtime branch is proven contained in `main` before any
  worktree/remote-branch cleanup; no unrelated local changes are deleted.

## Verification

- `node .claude/scripts/validate-docs.cjs docs`
- `git diff --check`
- `git status --short --branch`
- `git branch -a -vv` and `git worktree list`

## Completion evidence

- `main` is the only local/remote branch and the only registered worktree.
- PR #70 merged at `0788ee3ab6b189d5b08aaf78d0b4d6418951ada4`; the stale
  artifact runtime branch was patch-equivalent to merged commit `9f20589`.
- Documentation validation checked 15 Markdown files, 62 internal links, and
  41 configuration keys. The remaining code-reference/config-token warnings
  are heuristic warnings from the existing validator, not broken links.
- `git diff --check` passes. Unrelated working-tree changes remain untouched.
