# Autonomous MVP orchestrator

`scripts/autonomous-development.ps1` builds the approved CoupleFinance backlog with AI agents, in parallel, and
stops when the integrated mobile MVP is **ready for human acceptance testing**. The human never merges intermediate
work: everything is integrated into `integration/mvp`; `main` stays untouched until the human merges the final
Pull Request. Rules: [CLAUDE.md "Autonomous development orchestration"](../../CLAUDE.md#autonomous-development-orchestration).

```powershell
# Preview: DAG, runnable Issues, waves, worktrees, agent roles, validation steps and, after an interruption,
# the RECOVERY PREVIEW. Read-only and enforced: no fetch, and only read-only git/gh commands are allowed.
powershell.exe -ExecutionPolicy Bypass -File .\scripts\autonomous-development.ps1 -DryRun
# Same, as if every Issue were already approved (planning preview only)
powershell.exe -ExecutionPolicy Bypass -File .\scripts\autonomous-development.ps1 -DryRun -AssumeApproved

# Real run (also the restart command after any crash, token/session expiration or reboot)
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
 │       -> security-reviewer (if relevant) -> mobile-ux-reviewer (if relevant) -> fix -> ... -> VALIDATED|BLOCKED
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
5. After `-MaxReviewCycles` (default 3) with BLOCKER/HIGH or a red gate left: the Issue is `BLOCKED`, its dependents
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
3. the validated commit is merged into `integration/mvp` with `--no-ff --signoff` (idempotent, see below) and the trailers
   `Integrates-Issue: #N`, `Integrated-Branch`, `Reviewed-Head`; the merged tree must equal the validated tree;
4. `integration/mvp` and the Issue branch are pushed (never forced); dependents become runnable.

At start-up `origin/main` is merged into `integration/mvp` when it moved: a clean merge is gated before it is committed
(otherwise aborted); conflicts or failures become an `integration-validator` repair item.

## Crash recovery

Claude Code sessions, workers and the orchestrator are disposable: any of them may die at any moment (token/session
expiration, timeout, network failure, closed terminal, killed PowerShell, machine restart). Nothing depends on a
conversation history. The recoverable source of truth is:

- Git: `Integrates-Issue: #N` trailers and validated heads on `integration/mvp`, `feature|fix/<N>-*` branches, the
  worktrees with their commits and uncommitted/untracked files;
- GitHub: open Issues, open PRs to `main` (Issue then left to the human flow), PRs merged into `main`;
- `.autonomous-dev/state/<issue-N|repair-*>.json`: a per-item **checkpoint**, never trusted blindly, and
  `.autonomous-dev/runs/<run-id>/` logs (timeline, prompts, agent reports, findings).

**After any interruption, rerun exactly the same command.**

### Checkpoints

| Phase | Persisted when | Resumes as |
|---|---|---|
| `PLANNED` | worktree ready, before the worker process starts | implementation |
| `RUNNING` | before the implementation agent starts | recovery agent if commits/files exist, else implementation |
| `IMPLEMENTED`, `TESTING`, `REVIEWING` | after the agent / before the gate / before reviewers (gate result and each review persisted with its HEAD and diff fingerprint) | review loop: a passed gate for the same HEAD and reviews of unchanged scopes are reused |
| `FIXING` | before the fix agent, with `PendingFix` (cycle, findings file, HEAD) | the same fix cycle with the same findings (no extra review cycle) |
| `VALIDATED` | gate green, no BLOCKER/HIGH (`Head` = validated commit) | integration |
| `INTEGRATING` | before each integration step (`IntegrationStep` = check / sync / merge / push) | idempotent integration |
| `INTEGRATED`, `BLOCKED` | terminal (BLOCKED keeps worktree, branch, merge state and logs) | skipped (`-RetryBlocked` retries) |
| `INTERRUPTED` | a worker died, or Claude was unavailable (`InterruptedPhase`, `InterruptKind` AUTH/TRANSIENT) | from `InterruptedPhase` |

### What happens on restart

1. A stale lock (dead PID, or a PID reused after a reboot: start time recorded) is ignored.
2. An interrupted merge in the integration worktree is rolled forward, never discarded: an integration merge of a
   validated head is concluded; an interrupted `main` sync (clean, staged) is gated again. Anything else stops the run.
3. A validated merge that was committed but not pushed is pushed.
4. Every checkpoint is reconciled with Git (`Get-RecoveryAction`), and a report is printed:

   ```text
   RECOVERY DETECTED
   #76 INTEGRATED -> skip (already on the integration branch (state said VALIDATED; Git wins, state corrected))
   #77 INTERRUPTED during REVIEWING -> resume review (... 2 commit(s) kept, passed gate and unchanged reviews reused)
   #68 INTERRUPTED during RUNNING -> recovery agent (implementation interrupted with uncommitted work)
   #69 WAITING -> dependency #68
   #73 RUNNABLE -> continue
   ```

   - on the integration branch (trailer or validated head reachable) -> `INTEGRATED`, never merged again;
   - worker process still alive (PID + start time) -> adopted;
   - validated and branch unchanged -> integrated; branch moved -> re-validated;
   - commits and/or uncommitted/untracked files -> continued by a **new** Claude process whose prompt carries a
     recovery context: `git status`, commits and diff against `origin/integration/mvp`, the orchestration timeline and
     the previous log directories, plus the rule to never reset/clean/stash/rebase/restore that work;
   - state `INTEGRATED` without trace on the integration branch -> re-validated (Git wins).
5. A relaunched worker first waits for an agent orphaned by its dead predecessor (`<key>.agent.pid`), so two agents
   never work in the same worktree; an interrupted conflict resolution is continued by a new agent (never aborted).
6. Blocked items only stop their dependents; unrelated DAG branches continue.

Nothing is ever reset, cleaned, stashed or rebased; worktrees with uncommitted work are never removed. The only
`merge --abort` left is on a merge the orchestrator itself just created and that holds no agent work.

### Claude unavailable

A Claude process that fails without a result is classified: **AUTH** (session/token expired, not logged in,
usage/credit limit) pauses the run: no new agent starts, running workers checkpoint themselves as `INTERRUPTED`, and
the orchestrator exits with code `3`. **TRANSIENT** (overloaded, rate limit, network) relaunches only that item later
(exponential back-off, max 4, then pause). Anything else (crash, timeout) relaunches the worker from its checkpoint
(max 3, then `BLOCKED`). `-RetryBlocked` retries blocked items, keeping their work.

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
| 3 | paused: Claude Code unavailable (session/token expired, usage limit, persistent overload/network); restore it, then rerun the same command |
| 1 | unsafe/unexpected state (diverged integration branch, rejected push, dirty integration worktree, unattributable merge in progress, missing tools) |

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
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\orchestrator\tests\Run-Tests.ps1                     # unit, recovery and worker tests (fake claude, temp repos, real killed processes)
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\orchestrator\tests\Run-Tests.ps1 -IncludeDryRunSmoke # + real DryRun: refs (incl. remote-tracking), index, worktrees, .autonomous-dev unchanged
```

The Issue gate also runs these tests whenever orchestrator files change.
