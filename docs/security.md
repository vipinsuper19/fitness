# Supply-chain security

This page covers CI and repository configuration only. None of it changes app code, the Docker image or how deploys work.

## Dependency updates (Dependabot version updates)

`.github/dependabot.yml` asks for version-update PRs every Monday (Asia/Kolkata) for three ecosystems:

- `npm` in `backend/`
- `github-actions` in `/`
- `gradle` in `android/`

Minor and patch updates are grouped into one PR per ecosystem. Major updates come as separate PRs.

This file only configures version updates. Dependabot alerts, security updates and other repository security settings are not changed here.

## npm audit in backend CI

The `backend` workflow runs `.github/scripts/npm-audit-prod.cjs` right after `npm ci`.

The script runs `npm audit --omit=dev`, so it checks **production dependencies only**. It fails the build when:

- there is a **high or critical** advisory that isn't listed in `.github/npm-audit-allowlist.json`;
- an allowlist entry is past its `reviewBy` date, so the build starts failing on that date until someone fixes the dependency or renews the entry;
- an allowlist entry no longer matches any reported advisory, so stale entries get removed.

Low and moderate advisories, and advisories in dev-only dependencies, don't fail the build.

To add an entry, key it by the GHSA id and give it the `package`, `severity`, `reason` (whether it can be reached in this API) and a `reviewBy` date no more than about two weeks out.

### Current allowlist (October 2026)

| Advisory | Package | Reachable? | Plan |
|---|---|---|---|
| GHSA-q7qp-rjg7-q26m (high): DoS via a pending async fileFilter | multer (through @nestjs/platform-express) | No: no route accepts file uploads | Needs a Nest release that bumps multer, or an npm override (platform-express pins multer to exactly 2.4.0) |

## Pinned GitHub Actions

Every third-party action in `.github/workflows/` is pinned to a full commit SHA, with the version in a comment, for example `actions/checkout@<sha> # v4.4.0`.

Dependabot (`github-actions`) understands this format: it updates both the SHA and the comment.

To bump an action by hand, resolve the tag's commit with `gh api repos/<owner>/<repo>/commits/<tag> -q .sha`.

## CodeQL

`.github/workflows/codeql.yml` runs CodeQL for `javascript-typescript` with the default query suite. It covers the backend and any other JS/TS code in the repo.

It runs on every pull request, on pushes to `main` and weekly (Mondays 09:00 IST). It needs no build (`build-mode: none`).

Results appear under the repository's Security → Code scanning tab. The job has only `security-events: write`, plus read access to contents and actions.

The init and analyze actions are pinned to a SHA, like every other action.


History: GHSA-27r3-jxmh-p828 (body-parser, high, reachable) was fixed by a lockfile-only bump of body-parser from 2.3.0 to 2.4.0, then removed from the allowlist. The multer entry stays: @nestjs/platform-express pins multer to exactly 2.4.0, so a lockfile-only update can't reach the fixed 2.5.0.
