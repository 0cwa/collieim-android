# Collie agent notes

This file is for autonomous agents. Human contributors should follow [CONTRIBUTING.md](.github/CONTRIBUTING.md).

- Workflow: `0cwa/collie-release-engineering` `docs/DEVELOPMENT-CYCLE.md`. Change app code directly in ordinary PRs.
- Every non-merge commit carries one `Layer: <name>` trailer naming an entry in `tooling/layers.toml`, e.g. `Layer: settings`. Sign commits and confirm that GitHub verifies their signatures before landing.
- Work is sliced by feature: a PR changes one `feature` layer, plus any `infra` or `upstream` layers. A second feature is a second PR. A feature's tests and docs use its layer. A new feature adds its entry to `tooling/layers.toml` in the same PR. Land PRs with a merge commit, not squash or rebase, so every commit keeps its layer.
- Use a dedicated `origin/main` worktree per feature lane; avoid `.patch` or `GIT_INDEX_FILE` staging across a dirty checkout.
- Before pushing, run `python3 tooling/git-hooks/install.py` or `python3 tooling/ci/preflight.py` to catch unregistered `Layer:` trailers, unsigned commits, and Fastlane metadata errors locally.
- For feature lanes, verify focused unit tests and debug instrumentation APKs locally; leave full dual unsigned release APK builds to CI unless directly verifying release or packaging changes.
- Signal and Molly upgrades: merge them with `python3 tooling/upstream/merge-upstream.py signal <tag>` or `molly <branch>` (see `docs/SIGNAL-DONATION-REMOVAL.md`). Upstream commits stay unsigned and without trailers; the signed merge commit carries `Layer: upgrade` and `Upstream:`, which CI checks against the upstream repository.
- Keep upstream changes mergeable: use small hooks and isolated resources, and track deliberate removals; see `docs/SOFT-FORK-ARCHITECTURE.md`.
- Security reviews should record provenance, trust domains, seams, and falsification evidence; see `docs/security/REVIEW-METHODOLOGY.md`.
- Add nothing to the repo root; put new files under `docs/`, `tooling/` or `.github/`.
- Backups: `docs/agents/backups.md` (V2-only rule for Collie features; keep legacy V1 `.backup` code upstream-intact for Molly restore compatibility).
- Releases: `docs/RELEASING.md`. Build and publish the APK first. Tooling changes go in separate PRs and never delay a release.
- For changes to screens, navigation, UI behavior, or user-facing feature flows, capture relevant screenshots from the actual running app on an API 35 emulator before merging. Show before and after and affected states when useful to explain the change; choose evidence appropriate to the change, without requiring a fixed matrix. Link the retained screenshots in the PR description so reviewers can inspect them. Screen security (FLAG_SECURE) blanks screenshots: turn it off for the capture and restore it afterward. Use relevant tests or runtime evidence for changes without a UI effect.
- Website UI changes also require browser screenshots at affected viewport widths, embedded in the PR description before handoff or merge.
- Instrumentation: install the app and test APKs from the same build. A run that collected 0 tests failed.
- For new or modified screens, dialogs, and navigation flows, prefer a deterministic instrumentation test that drives the flow and captures the required API 35 screenshots when practical. Reserve manual emulator clicking for OS or third-party app boundaries. Run the curated Stable and TrailBlazer Beta API 35 UI smoke suites locally with `python3 tooling/ci/run_ui_smoke.py --edition stable` or `--edition beta`.
- Put new UI only where the request says. If it doesn't say, ask.
- Italicize the visible title of every Collie-added setting outside the Collie settings screen in both editions. In Compose, use `asCollieSettingTitle()`. In legacy settings, use `DSLSettingsText.ItalicModifier`. Keep descriptions, values, and the controls on the Collie settings screen in their normal style.
- Ported upstream changes keep their original author (`git am`, or a `Co-authored-by:` trailer).
- CodeRabbit: its rate-limit and "review in progress" comments are not findings. Translation strings are excluded (`.coderabbit.yaml`).
- Encrypted push follows the applicable versioned contract and exact pin in `0cwa/colliesocket`. If the contract is wrong or unclear, open an issue or PR in that repo and continue here with the pinned contract as written; do not reinterpret a field here.
- This repo is public. Name only projects with a public repo, or Collie Pack, and describe anything else generically ("the push provider", "the distributor").
