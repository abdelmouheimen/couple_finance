# Autonomous MVP orchestrator

`scripts/autonomous-development.ps1` builds the approved CoupleFinance backlog with AI agents, in parallel, and
stops when the integrated mobile MVP is **ready for human acceptance testing**. The human never merges intermediate
work: everything is integrated into `integration/mvp`; `main` stays untouched until the human merges the final
Pull Request. Rules: [CLAUDE.md "Autonomous development orchestration"](../../CLAUDE.md#autonomous-development-orchestration).

```powershell
# Preview: DAG, runnable Issues, waves, worktrees, agent roles, validation steps. Changes nothing.
powershell.exe -ExecutionPolicy Bypass -File .\scripts\autonomous-development.ps1 -DryRun
# Same, as if every Issue were already approved (planning preview only)
powershell.exe -ExecutionPolicy Bypass -File .\scripts\autonomous-development.ps1 -DryRun -AssumeApproved

# Real run
powershell.exe -ExecutionPolicy Bypass -File .\scripts\autonomous-development.ps1 -Target mvp -Parallelism 3 -AutoMergeIntegration
```

## Before the first real run

1. Merge the orchestrator change into `main` (agents and prompts are read from the integration branch, which is
   created from `main`).
2. Approve the Issues: every Issue to build needs `status:ready`, no `status:blocked`, no `## Open question`
   section, an acceptance-criteria checklist and a `## Dependencies` section. The orchestrator never changes labels or
   Issue bodies. `-DryRun` lists every missing approval.
3. Docker Desktop running, `gh auth login` done, Node 22+, a JDK 17+ for Gradle, `claude` logged in.

## Architecture

```text
autonomous-development.ps1  (orchestrator, single instance, lock file)
 ├─ planning: AutonomousDev.Planning.psm1   pure: dependency parsing, DAG, cycles, waves, relevance, parsing
 ├─ runtime : AutonomousDev.Runtime.psm1    processes + timeouts, git, claude agents, gates, state files
 ├─ workers : Invoke-IssueWorker.ps1         one background process per Issue / repair item
 │     develop (issue-developer | integration-validator)  -> gate -> code-reviewer
 │       -> security-reviewer (if relevant) -> mobile-ux-reviewer (if relevant) -> fix -> ... -> approved|blocked
 ├─ integration (serialized in the orchestrator): sync with integration tip -> re-gate -> merge --no-ff --signoff -> push
 └─ final acceptance: full gate, expo config, android bundle, API journey, holistic UX review, optional APK, PR -> main
```

Every agent is a fresh `claude -p --agent <name>` process with a short prompt; it loads its own context from
`CLAUDE.md`, the Issue and only the documents the Issue references. Fix passes receive only the findings.

| Agent | Used for | Writes code |
|---|---|---|
| `issue-developer` | implement an Issue, fix findings, resolve merge conflicts, UX fixes | yes (its branch) |
| `code-reviewer` | every Issue / repair diff | no |
| `security-reviewer` | backend production code, API contract, infra/CI, dependencies, mobile auth/session/token/storage/API code, or security-related Issue titles | no |
| `mobile-ux-reviewer` | diffs touching `mobile/app` or `.tsx` under `mobile/src`; holistic review at the end | no |
| `integration-validator` | failing integrated builds, `main` sync conflicts, the API acceptance journey | yes (`integration-fix/*`) |

## Parallel worktrees

- Worktrees live in a sibling directory, `<repo>.worktrees\` (`-WorktreeRoot` to change): `issue-76`, `issue-77`,
  `issue-69`, `repair-*`, and `integration` (the checkout of `integration/mvp`). Outside the repository, agents do not
  load the parent `CLAUDE.md` twice and tools never scan other worktrees.
- Each Issue: branch `feature/<N>-<slug>` (`fix/` for `bug`), created from `origin/integration/mvp`, or the existing
  `feature|fix/<N>-*` branch when one exists (resume).
- Up to `-Parallelism` workers run at once (default 3); `-MaxIssues` caps new Issues per run.
- A worktree is removed only after its branch is integrated and pushed, and only when it has no uncommitted changes
  (`git worktree remove` without `--force`). Blocked work keeps its worktree, branch and logs.

## Review / fix loop

1. Deterministic gate on the net change areas (the CI commands): `backend\gradlew.bat build --no-daemon`
   (`-Werror`, unit/property tests, Testcontainers + Liquibase, Spring Modulith/ArchUnit, OpenAPI drift) and/or
   `npm ci` (when the lock file changed), `npm run check:api`, `npm run verify` (lint, format, typecheck, Jest/RNTL).
2. Reviews only on a green gate, each in a fresh process, on `git diff origin/integration/mvp...HEAD`.
3. Any gate failure or BLOCKER/HIGH finding goes back to a fresh fix pass (`FIX MODE`); in-scope MEDIUM findings get
   one fix pass; LOW findings are recorded. Rejected findings must be justified and are shown to the reviewer next time.
4. Reviewers re-run only when the part of the net diff they cover changed (per-file fingerprints), or when they had
   open findings: no repeated reviews when nothing relevant changed.
5. After `-MaxReviewCycles` (default 3) with BLOCKER/HIGH or a red gate left: the Issue is `blocked`, its dependents
   wait, unrelated DAG branches continue.

An agent stops an Issue only for the human stop conditions (contradicting requirements, missing credential, change of
an approved decision, action against `main`/production): category `HUMAN_DECISION`, `REQUIREMENTS_CONFLICT`,
`MISSING_CREDENTIAL` or `MAIN_OR_PRODUCTION` in its `ORCHESTRATOR-RESULT` line.

## Integration strategy

Serialized, one approved item at a time:

1. the item's worktree must be clean and at the approved commit;
2. if `origin/integration/mvp` moved, it is merged into the item branch (`--no-ff --signoff`); a conflict is aborted
   and handed to a conflict-resolution agent (regenerates generated files, never edits integrated changesets); then
   the worker re-runs the gate and only the reviews whose scope changed (max 5 re-syncs);
3. the branch is merged into `integration/mvp` with `--no-ff --signoff` and the trailers
   `Integrates-Issue: #N`, `Integrated-Branch`, `Reviewed-Head`; the merged tree must equal the validated tree;
4. `integration/mvp` and the Issue branch are pushed (never forced); dependents become runnable.

At start-up `origin/main` is merged into `integration/mvp` when it moved: a clean merge is gated before it is committed
(otherwise aborted); conflicts or failures become an `integration-validator` repair item.

## Resumability

Rerunning the same command continues safely. State comes from:

- Git: `Integrates-Issue: #N` trailers on `integration/mvp` (done), existing `feature|fix/<N>-*` branches and worktrees;
- GitHub: open Issues, open PRs to `main` (Issue then left to the human flow), PRs merged into `main`;
- local state files `.autonomous-dev/state/<issue-N|repair-*>.json` (phase, approved commit, review fingerprints,
  cycles, blocked reason, worker PID).

Workers still running from an interrupted orchestrator are adopted by PID; dead workers are relaunched (bounded);
approved items are integrated; interrupted implementations resume on their branch. `-RetryBlocked` retries blocked
items (keeping their work).

## Logs

`.autonomous-dev/runs/<run-id>/` (git-ignored): `orchestrator.log`, and per item `worker.log` (timeline), every agent's
`*.prompt.md`, `*.json` (raw output), `*.result.md` (report), `findings-c<N>.md`, gate logs per cycle; `final/` holds
the final gates and UX review; `HUMAN-ACCEPTANCE.md` the final report. Agents are told never to print tokens,
passwords or verification links; prompts contain no secrets.

## Cost controls

`-Parallelism` (3), `-MaxIssues`, `-MaxReviewCycles` (3), `-MaxUxCycles` (2), `-MaxRepairCycles` (2),
`-AgentTimeoutMinutes` (90, kills the process tree), `-MaxBudgetUsd` per agent process, `-Model`. Reviews skip
unchanged scopes, run only on a green gate, security/UX reviewers only when relevant, and every prompt points to the
minimum context. Each agent's reported cost is accumulated in its item state (`CostUsd`).

## When does the run stop?

| Exit | Meaning |
|---|---|
| 0 | `READY FOR HUMAN ACCEPTANCE TESTING` (or a dry run) |
| 2 | human input needed: Issues without approval, open questions, blocked Issues (with their waiting dependents), unresolved final validation/journey/UX BLOCKER-HIGH findings |
| 1 | unsafe/unexpected state (diverged integration branch, rejected push, dirty integration worktree, missing tools) |

## Human acceptance testing

The run reached acceptance when it prints the green banner `READY FOR HUMAN ACCEPTANCE TESTING`, exits `0`, writes
`.autonomous-dev/HUMAN-ACCEPTANCE.md` (copy in the run directory) and has opened the PR `integration/mvp -> main`.

```powershell
# Android emulator (start it first from Android Studio)
powershell.exe -ExecutionPolicy Bypass -File .\scripts\start-mvp-acceptance.ps1 -Device emulator
# Physical Android phone over USB (USB debugging on, Expo Go installed, adb on PATH)
powershell.exe -ExecutionPolicy Bypass -File .\scripts\start-mvp-acceptance.ps1 -Device usb
```

The helper starts PostgreSQL (Docker Compose) and the backend (`local` profile) from the integration worktree, sets
`EXPO_PUBLIC_API_BASE_URL` (`http://10.0.2.2:8080` for the emulator; `adb reverse` + `http://localhost:8080` over USB),
and runs `npx expo start --android`. With `-BuildAndroidApk` (and an Android SDK) the run also produces a debug APK.

## Tests

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\orchestrator\tests\Run-Tests.ps1                     # unit + worker smoke test (fake claude, temp repo)
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\orchestrator\tests\Run-Tests.ps1 -IncludeDryRunSmoke # + real DryRun, asserts no repository change
```

The Issue gate also runs these tests whenever orchestrator files change.
