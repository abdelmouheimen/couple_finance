# CoupleFinance — Setup Autonomous Development

## Objective

Configure CoupleFinance so that Claude Code can autonomously process the GitHub backlog with the following model:

```text
GitHub Issues
      ↓
external PowerShell orchestrator
      ↓
select next READY Issue
      ↓
NEW Claude Code process / fresh context
      ↓
issue-developer
      ↓
implementation
      ↓
tests
      ↓
independent reviews
      ↓
corrections
      ↓
commit
      ↓
push
      ↓
Pull Request
      ↓
Claude process terminates
      ↓
orchestrator searches next independent READY Issue
      ↓
NEW Claude Code process
```

The fundamental execution model is:

> **One Issue = one fresh Claude Code context = one branch = one Pull Request.**

The human Tech Lead remains responsible for:

- product decisions;
- architecture decisions requiring approval;
- final PR review;
- merging Pull Requests.

Claude MUST NEVER automatically merge Pull Requests.

---

# 1. Inspect the current repository first

Before modifying anything, inspect:

```bash
git status
git branch --show-current
git log --oneline -10
```

Inspect:

- `CLAUDE.md`
- `.claude/agents/`
- existing scripts
- current GitHub Issues
- current Pull Requests

Run:

```bash
gh auth status
gh repo view
gh issue list --state open --limit 200
gh pr list --state open
claude --version
```

If `git`, `gh` or `claude` is unavailable, STOP and report exactly what is missing.

If GitHub authentication is unavailable, STOP.

Do not modify application code during this setup.

---

# 2. Preserve existing configuration

Do not blindly overwrite existing:

- `CLAUDE.md`
- `.claude/agents/backlog-manager.md`
- `.claude/agents/issue-developer.md`
- reviewer agents.

Inspect them first.

Merge the requirements below into the existing configuration.

Preserve stricter existing rules.

Do not weaken:

- architecture rules;
- security rules;
- testing requirements;
- database rules;
- Money rules;
- AI trust boundaries;
- human approval boundaries.

---

# 3. Required agent structure

Ensure the following structure exists:

```text
.claude/
└── agents/
    ├── backlog-manager.md
    ├── issue-developer.md
    ├── code-reviewer.md
    └── security-reviewer.md
```

Create missing directories/files when required.

Do not create a long-running `development-orchestrator` agent.

The backlog execution loop will be managed externally by PowerShell so
that each Issue receives a fresh Claude Code context.

---

# 4. Configure issue-developer

Ensure `.claude/agents/issue-developer.md` represents an autonomous
Senior Staff Software Engineer responsible for exactly ONE GitHub Issue.

It must own:

```text
Issue analysis
→ dependency verification
→ update main
→ branch creation
→ implementation
→ Liquibase when required
→ OpenAPI when required
→ tests
→ verification
→ self-review
→ independent reviews
→ corrections
→ commit
→ push
→ Pull Request
→ STOP
```

It MUST:

- read `CLAUDE.md`;
- read the complete GitHub Issue;
- read referenced BR-xxx rules;
- read only relevant approved documentation;
- inspect relevant code and tests;
- implement only Issue scope;
- follow module boundaries;
- enforce database invariants;
- enforce household isolation;
- treat AI output as untrusted;
- run required tests;
- inspect the complete diff;
- fix BLOCKER/HIGH findings;
- create the PR;
- stop after PR creation.

It MUST NOT:

- merge the PR;
- start another Issue;
- push directly to `main`;
- invent product requirements;
- make architecture decisions requiring human approval;
- bypass tests;
- bypass CI;
- weaken security.

Ensure the following rules exist explicitly:

```text
Do NOT automatically start another Issue.

Do NOT merge the Pull Request.
```

---

# 5. Configure independent code reviewer

Ensure:

```text
.claude/agents/code-reviewer.md
```

exists.

The code reviewer is independent from the implementation agent.

It reviews the complete current Issue diff.

It checks:

- acceptance criteria;
- BR-xxx compliance;
- architecture;
- module boundaries;
- domain modeling;
- transaction boundaries;
- concurrency;
- database constraints;
- JPA behavior;
- query behavior;
- N+1 risks;
- Money handling;
- error handling;
- tests;
- regression risks;
- unnecessary complexity;
- unrelated changes.

Findings must be classified:

```text
BLOCKER
HIGH
MEDIUM
LOW
```

Each finding must contain:

- severity;
- file/location;
- problem;
- impact;
- recommended correction.

The reviewer must not implement fixes.

Do not include generic praise.

Report actionable findings only.

---

# 6. Configure independent security reviewer

Ensure:

```text
.claude/agents/security-reviewer.md
```

exists.

It reviews the complete current Issue diff for:

- authentication;
- authorization;
- household isolation;
- IDOR;
- privilege escalation;
- mass assignment;
- input validation;
- information leakage;
- sensitive logs;
- secrets;
- SQL injection;
- transaction/race problems;
- personal-data isolation;
- file-upload security;
- AI trust boundaries;
- consent/privacy where applicable.

Household isolation is critical.

A user must never obtain another household's protected resources.

PERSONAL data must remain private according to approved business rules.

AI output must remain untrusted.

Findings must be classified:

```text
BLOCKER
HIGH
MEDIUM
LOW
```

The reviewer must not implement fixes.

---

# 7. Configure the review loop

Ensure `issue-developer` uses the independent reviewers before presenting
the PR as ready.

Expected lifecycle:

```text
implementation
     ↓
tests
     ↓
self-review
     ↓
code-reviewer
     ↓
security-reviewer
     ↓
analyze findings
     ↓
fix valid BLOCKER/HIGH
     ↓
add/update tests
     ↓
rerun affected verification
     ↓
review final diff
     ↓
commit/push
     ↓
PR
```

Do not blindly apply reviewer suggestions.

If a reviewer recommendation conflicts with:

- approved BR;
- architecture;
- ADR;
- security model;
- Issue scope;

STOP when human approval is required.

No known BLOCKER or HIGH finding may remain when a PR is presented as
ready.

---

# 8. Add fresh-context rules to CLAUDE.md

Merge the following policy into `CLAUDE.md`.

Do not duplicate an equivalent existing section.

```text
### Fresh-context execution

Autonomous backlog development uses one fresh Claude Code process per
GitHub Issue.

Each Issue implementation must run in an independent Claude Code context.

The Issue developer must not rely on conversation context from previous
Issues.

All required context must be reconstructed from durable project sources:

- CLAUDE.md
- the GitHub Issue
- referenced BR-xxx rules
- relevant ADRs
- relevant documentation
- current code
- current tests

The external development orchestrator is responsible for selecting the
next READY Issue and starting a new Claude Code process.

One Issue = one development context = one feature/fix branch = one Pull
Request.

Dependent Issues must not start until their prerequisite implementation
has been merged into main.

Independent READY Issues may be implemented while other Pull Requests
wait for human review.

Claude must never merge Pull Requests automatically.
```

Also ensure `CLAUDE.md` states that the autonomous loop stops when:

- no READY Issue remains;
- human input is required;
- a blocking failure occurs;
- continuing would be unsafe.

---

# 9. GitHub labels

Inspect existing labels first:

```bash
gh label list
```

Ensure these labels exist:

```text
status:ready
status:blocked

priority:high
priority:medium
priority:low
```

Do not create duplicates.

If missing, create them.

Also preserve/use existing type/area labels where appropriate.

Do not arbitrarily relabel Issues during this setup.

---

# 10. Dependency format

Inspect the GitHub Issues created by backlog-manager.

Dependencies should use a machine-readable section:

```md
## Dependencies

None
```

or:

```md
## Dependencies

- #12 SHARED-001
- #17 HOUSEHOLD-002
```

Prefer real GitHub Issue numbers.

Do not invent dependencies.

If the existing backlog uses another consistent machine-readable
dependency representation, preserve it and adapt the orchestrator
accordingly.

Do not rewrite every Issue unnecessarily.

---

# 11. READY semantics

An Issue is eligible for autonomous implementation only when ALL of the
following are true:

1. Issue is OPEN.
2. Issue represents approved implementation work.
3. Issue is `status:ready`.
4. Issue is NOT `status:blocked`.
5. Acceptance criteria are sufficiently defined.
6. Required human product/architecture decisions are resolved.
7. No active Pull Request already implements the Issue.
8. Every implementation dependency is merged into `main`.

A dependency is NOT considered satisfied merely because:

- work started;
- a branch exists;
- a PR exists;
- Claude says implementation is complete.

For code dependencies, the prerequisite implementation must be merged
into `main`.

---

# 12. Selection order

When multiple Issues are eligible, use:

1. dependency order;
2. explicit GitHub priority;
3. approved backlog implementation order;
4. GitHub Issue number only as final tie-breaker.

Do not blindly select the lowest Issue number.

Default to sequential Claude execution.

Do not run several implementation agents concurrently unless explicitly
authorized by the human.

Several independent PRs may nevertheless remain open simultaneously
because each Claude process terminates after creating its PR.

---

# 13. Create the external PowerShell orchestrator

Create:

```text
scripts/autonomous-development.ps1
```

Create `scripts/` if necessary.

The script must support:

```powershell
-DryRun
-MaxIssues <number>
```

Example:

```powershell
.\scripts\autonomous-development.ps1 -DryRun
```

and:

```powershell
.\scripts\autonomous-development.ps1 -MaxIssues 1
```

---

# 14. PowerShell orchestrator responsibilities

The script must:

1. verify `git`, `gh` and `claude`;
2. verify GitHub authentication;
3. verify it runs inside the expected repository;
4. require a clean working tree;
5. fetch current remote state;
6. inspect open Issues;
7. inspect open PRs;
8. identify READY Issues;
9. exclude blocked Issues;
10. exclude Issues already having an active PR;
11. parse Issue dependencies;
12. verify code dependencies are merged;
13. select the next eligible Issue;
14. start a NEW Claude Code process;
15. wait for that process to finish;
16. verify that the expected PR exists;
17. return to a predictable repository state;
18. refresh GitHub state;
19. select the next independent eligible Issue;
20. repeat until the limit or stop condition is reached.

The PowerShell process is the orchestrator.

Claude sessions must NOT recursively orchestrate the backlog themselves.

---

# 15. Fresh Claude invocation

For each selected Issue, invoke a NEW Claude Code process.

Use Claude Code's non-interactive/print invocation supported by the
installed Claude version.

Before relying on a CLI flag, verify it with:

```bash
claude --help
```

Do not assume unsupported CLI flags.

The prompt for each fresh process should be equivalent to:

```text
Use the issue-developer subagent.

Implement GitHub Issue #<N> autonomously according to CLAUDE.md.

This is a FRESH CONTEXT.

Do not assume any knowledge from previous Claude sessions.

Reconstruct required context from:
- CLAUDE.md
- GitHub Issue #<N>
- referenced BR-xxx rules
- relevant approved documentation
- relevant ADRs
- current implementation
- current tests

Own the complete lifecycle:
- analyze Issue
- verify dependencies
- update main
- create branch
- implement
- migrations when required
- OpenAPI when required
- tests
- verification
- self-review
- independent reviews
- corrections
- commit
- push
- create PR

Do not merge the Pull Request.

Do not start another Issue.

After PR creation, terminate the session.

Stop instead if:
- requirements conflict
- dependency is missing
- human architecture/product/security approval is required
- implementation cannot safely be completed
```

---

# 16. Repository state between Issues

The orchestrator must NOT accidentally destroy work.

Before each Issue:

```bash
git status
```

must be clean.

After an Issue process creates its PR, ensure its branch is pushed.

Before selecting another Issue, return to `main` safely.

Update `main` only with fast-forward behavior.

Never:

- reset away uncommitted work;
- discard files automatically;
- force checkout through conflicts;
- force-push;
- delete branches containing unmerged work.

If repository state is unexpected:

STOP.

---

# 17. Open PR detection

Before starting an Issue, determine whether an existing PR already
implements it.

Use GitHub data rather than assumptions.

Recognize Issue linkage through mechanisms such as:

```text
Closes #N
Fixes #N
Resolves #N
```

and the project's branch naming convention where useful.

If an Issue already has an active implementation PR:

SKIP it.

Do not create duplicate implementation PRs.

---

# 18. Dependency verification

Do not assume `CLOSED` automatically means a code dependency is satisfied.

Where a prerequisite represents implementation work, verify the related
Pull Request was merged.

Required model:

```text
dependency Issue
      ↓
implementation PR
      ↓
MERGED
      ↓
dependency satisfied
```

If a dependency is not merged:

- do not start the dependent Issue;
- look for another independent READY Issue.

If none exists:

STOP normally.

---

# 19. Dry-run mode

`-DryRun` MUST NOT:

- invoke implementation Claude;
- modify code;
- create branches;
- create commits;
- push;
- create PRs;
- modify Issues.

It should report:

```text
Candidate Issue:
#N
identifier
title
priority
dependencies
dependency status
reason it is eligible
```

If nothing is eligible, explain why.

Dry-run mode is mandatory for the first validation.

---

# 20. Maximum Issue limit

`-MaxIssues` limits the number of fresh Claude implementation processes
started by one execution.

Default to a conservative value.

The first real execution must use:

```powershell
-MaxIssues 1
```

Do not run many Issues during initial setup validation.

---

# 21. Stop conditions

The orchestrator must stop when:

### No READY work

No eligible Issue remains.

### Human decision required

An Issue exposes an unresolved:

- product decision;
- business rule;
- architecture decision;
- security/privacy decision;
- authentication decision;
- Money-model decision;
- AI trust-boundary decision.

### Implementation failure

Claude exits unsuccessfully or does not create the expected PR.

### Repository problem

Working tree becomes unsafe or unexpected.

### Verification failure

A PR cannot legitimately be presented as ready because required checks
failed.

Do not silently skip serious blockers and continue through dependent work.

---

# 22. Human merge boundary

The orchestrator and all Claude agents MUST NEVER:

- merge PRs;
- enable auto-merge;
- approve their own PRs;
- push directly to `main`;
- bypass branch protection;
- bypass required CI;
- force-push `main`.

Human review and merge are mandatory.

This is a hard safety boundary.

---

# 23. Token/context optimization

Each Issue must start in a fresh Claude context.

However, do not compensate by loading the entire repository documentation
into every context.

The Issue should guide Claude toward relevant context.

For example:

```md
## Business rules

- BR-MON-01
- BR-MON-02
- BR-MON-06

## Relevant documentation

- `docs/product/business-rules.md` — BR-MON
- `docs/architecture/domain-model.md` — Money
- `docs/architecture/database.md` — monetary persistence
```

Claude should read:

```text
CLAUDE.md
+
target Issue
+
referenced rules
+
relevant documentation
+
relevant code/tests
```

not every unrelated project document.

Do not read all documentation by default.

This is both a context-efficiency and engineering-focus requirement.

---

# 24. Do not over-optimize contexts

Use:

> one development context per Issue.

Do NOT create separate fresh contexts for:

- controller;
- service;
- repository;
- migration;
- tests.

That would repeatedly pay the cost of reconstructing the same Issue
context.

The development agent keeps one context for the entire Issue lifecycle.

Independent reviewers may use separate contexts/subagents when supported.

---

# 25. Initial validation procedure

After configuration is complete, DO NOT immediately implement Issues.

First execute only the dry run:

```powershell
.\scripts\autonomous-development.ps1 -DryRun
```

Validate that:

- repository is detected;
- GitHub works;
- READY Issues are found;
- blocked Issues are ignored;
- existing PRs are ignored;
- dependencies are parsed correctly;
- unmerged dependencies prevent execution;
- one correct candidate is selected.

If dry run fails:

fix the orchestration configuration only.

Do not implement application code.

---

# 26. First real execution

After dry run is successful, STOP and report readiness.

Do NOT automatically execute the first Issue during this setup task.

Report the command the human should use:

```powershell
.\scripts\autonomous-development.ps1 -MaxIssues 1
```

The human will explicitly decide when to start the first autonomous
implementation.

---

# 27. Expected first real lifecycle

When the human later executes:

```powershell
.\scripts\autonomous-development.ps1 -MaxIssues 1
```

expected behavior is:

```text
PowerShell
   ↓
find next READY Issue
   ↓
verify dependencies
   ↓
start NEW Claude process
   ↓
issue-developer
   ↓
read durable context
   ↓
create feature branch
   ↓
implementation
   ↓
tests
   ↓
self-review
   ↓
code-reviewer
   ↓
security-reviewer
   ↓
corrections
   ↓
verification
   ↓
commit
   ↓
push
   ↓
create PR
   ↓
Claude process exits
   ↓
PowerShell verifies PR
   ↓
STOP because MaxIssues = 1
```

The human then reviews and optionally merges the PR.

---

# 28. Later multi-Issue execution

Only after the first complete lifecycle has been validated should the
human use values such as:

```powershell
.\scripts\autonomous-development.ps1 -MaxIssues 3
```

or:

```powershell
.\scripts\autonomous-development.ps1 -MaxIssues 5
```

Example:

```text
#12 → PR #40 → waiting human review

#13 depends on #12
→ SKIP until #12 PR is merged

#14 independent
→ NEW Claude context
→ PR #41

#15 independent
→ NEW Claude context
→ PR #42
```

Never build dependent work on an unmerged feature branch.

---

# 29. CI expectations

Inspect existing GitHub Actions.

Do not replace working CI unnecessarily.

The target verification pipeline should cover applicable checks such as:

```text
compile
↓
unit tests
↓
PostgreSQL/Testcontainers integration tests
↓
architecture tests
↓
Liquibase validation
↓
OpenAPI drift
↓
dependency/security checks
↓
build
```

If required CI does not exist yet, report the gap.

Do not silently introduce a major CI redesign during this setup unless it
is already approved.

---

# 30. Final setup verification

Before declaring this setup complete, verify:

- [ ] `CLAUDE.md` contains fresh-context execution rules.
- [ ] `backlog-manager.md` exists.
- [ ] `issue-developer.md` exists.
- [ ] `code-reviewer.md` exists.
- [ ] `security-reviewer.md` exists.
- [ ] issue-developer handles exactly one Issue.
- [ ] issue-developer never merges.
- [ ] reviewers are independent.
- [ ] `scripts/autonomous-development.ps1` exists.
- [ ] script verifies required commands.
- [ ] script verifies GitHub authentication.
- [ ] script checks clean working tree.
- [ ] script discovers READY Issues.
- [ ] script ignores blocked Issues.
- [ ] script avoids duplicate active PRs.
- [ ] script parses dependencies.
- [ ] script requires implementation dependencies to be merged.
- [ ] script starts a fresh Claude process per Issue.
- [ ] script supports `-DryRun`.
- [ ] script supports `-MaxIssues`.
- [ ] script never merges.
- [ ] dry run executed successfully.
- [ ] no application code was modified during setup.
- [ ] no implementation Issue was started during setup.

---

# 31. Required final report

After completing this setup, report:

## Files created

List them.

## Files modified

List them and summarize the changes.

## Existing configuration preserved

Explain what was reused rather than overwritten.

## GitHub integration

Report:

- authentication status;
- repository detected;
- number of open Issues;
- number of READY Issues;
- number of blocked Issues;
- number of open PRs.

## Agent configuration

Report status of:

- backlog-manager;
- issue-developer;
- code-reviewer;
- security-reviewer.

## Orchestrator

Report:

- script path;
- dependency-detection strategy;
- PR-detection strategy;
- fresh-context Claude invocation actually supported by the installed CLI.

## Dry-run result

Report:

- selected Issue;
- dependencies;
- why it is eligible;

or explain why no Issue is currently eligible.

## Problems discovered

List unresolved problems.

## Final status

Use exactly one:

```text
AUTONOMOUS DEVELOPMENT SETUP READY
```

or:

```text
AUTONOMOUS DEVELOPMENT SETUP BLOCKED
```

If READY, finish with:

```powershell
.\scripts\autonomous-development.ps1 -MaxIssues 1
```

Do NOT execute that command automatically.

Do NOT implement any GitHub Issue as part of this setup task.