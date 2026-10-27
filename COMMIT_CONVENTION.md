# Commit Convention

> Branch rules live in `docs/提交与分支工作流.md` (Chinese, authoritative).
> This file only pins the commit message format.

## Branch policy

Two permanent branches only:

- `main`: stable production branch. Protected, no direct pushes.
- `develop`: integration line. Feature/fix branches merge here first.

Short-lived branches, delete immediately after merge:

- `feature/<slug>`, `fix/<slug>`, `chore/<slug>`, `docs/<slug>`, `ci/<slug>`: branch from `develop`, merge back to `develop`
- `hotfix/<slug>`: branch from `main`, merge to `main`, then back-merge to `develop`
- `release/vX.Y.Z`: only during a freeze window; tag `vX.Y.Z` then delete

Retired: `baseline/*`, `test/*`, `dev/*`, long-lived `fix/*`. Historical content is preserved as `archive/<repo>/<branch>` tags. Run `scripts/git/branch-audit.sh` (dry-run by default) before deleting anything.

## Commit message format

Use:

```text
<type>: <summary>
```

Examples:

```text
feat: add backend container delivery workflow
fix: correct flyway migration compatibility on empty databases
chore: remove obsolete test branches from remote
docs: add commit convention for baseline workflow
```

## Allowed types

- `feat`: new feature or user-visible capability
- `fix`: bug fix or regression repair
- `refactor`: structural code change without intended behavior change
- `chore`: tooling, workflow, cleanup, branch governance
- `docs`: documentation only
- `test`: test-only change
- `perf`: performance improvement
- `ci`: CI/CD workflow change

## Rules

- Keep the summary on one line.
- Use the imperative mood.
- Describe the actual change, not the intention.
- Split unrelated changes into separate commits.
- Infrastructure and deployment changes must be committed separately from business logic when possible.

## Merge rules

- `feature/*` `fix/*` `chore/*` → `develop`: squash merge (`gh pr merge --squash --delete-branch`)
- `develop` → `main`: `git merge --no-ff develop` so the release point stays visible
- Never commit directly on `main`
- Before deleting a temporary remote branch, confirm it is either merged into `main`, patch-equivalent to `main` (`git cherry` shows no `+` lines), or archived as an `archive/<repo>/<branch>` tag
