---
phase: 2
title: Validate plan and documentation consistency
status: completed
priority: P1
effort: 30m
dependencies:
  - 1
---

# Phase 2: Validate plan and documentation consistency

## Overview

Validate the changed Markdown, search for superseded remote-CI claims, and
review the diff for scope accuracy before exact-head CI.

## Requirements

- Use static checks only because local storage preflight blocks heavy builds.
- Preserve unrelated user work by operating only in the isolated worktree.
- Require exact-head PR Quality before merge.

## Related Code Files

- Modify: `plans/260804-1025-phase-3-evidence-reconciliation/**`
- Validate: all Phase 3 and Phase 4 plan references touched in Phase 1.

## Implementation Steps

1. Search affected files for stale “has not run remotely” claims.
2. Run repository Markdown/document validation available without heavy installs.
3. Inspect the scoped diff and confirm head/merge tree evidence.
4. Commit, push, open PR, and require exact-head CI before merge.

## Success Criteria

- [ ] Static validation passes.
- [ ] Scoped diff contains no unrelated files or secrets.
- [ ] Exact-head CI passes before merge.

## Risk Assessment

CI can expose unrelated infrastructure flakes. Diagnose and rerun only with
evidence; never weaken gates to land documentation.
