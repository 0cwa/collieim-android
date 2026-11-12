# Collie agent notes

- Workflow: `0cwa/collie-release-engineering` `docs/DEVELOPMENT-CYCLE.md`. Change app code directly in ordinary PRs.
- Every non-merge commit carries one `Layer: <feature>` trailer (lowercase, hyphens), e.g. `Layer: settings`.
- Signal and Molly upgrades: follow that file's "Upstream upgrades" section. Never `git merge` an upstream tag.
- Add nothing to the repo root; put new files under `docs/`, `tooling/` or `.github/`.
- Releases: `docs/RELEASING.md`. Build and publish the APK first. Tooling changes go in separate PRs and never delay a release.
- UI changes: open the changed screen on an API 35 emulator and screenshot it before merging. The screen security setting (FLAG_SECURE) blanks screenshots; turn it off for the test and back on after.
- Instrumentation: install the app and test APKs from the same build. A run that collected 0 tests failed.
- Put new UI only where the request says. If it doesn't say, ask.
- Ported upstream changes keep their original author (`git am`, or a `Co-authored-by:` trailer).
- CodeRabbit: its rate-limit and "review in progress" comments are not findings. Translation strings are excluded (`.coderabbit.yaml`).
- Encrypted push follows the applicable versioned contract and exact pin in `0cwa/colliesocket`. If the contract is wrong or unclear, open an issue or PR in that repo and continue here with the pinned contract as written; do not reinterpret a field here.
- This repo is public. Name only projects with a public repo, or Collie Pack, and describe anything else generically ("the push provider", "the distributor").
