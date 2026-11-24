# TrailBlazer development

Open TrailBlazer development PRs against `trailblazer-beta`. That branch is the
home for the Beta package, Finish Setup, Project log, and encrypted relay work.
Changes common to both products can be reviewed on main and ported as needed.
The conservative Stable maintenance line starts from its published-tag ancestry.

Ordinary Beta PRs run the same Layer, lint, and unit-test checks as main. Their
unsigned production build uses `prodWebsiteBeta` and produces
`im.collie.app.trailblazer`. Use the PR's source and tree records to tie any
test or screenshot to the build it came from.

For a local account-free smoke test, build `:app:assembleProdWebsiteBeta` from
the exact commit and use a disposable emulator signer. Synthetic relay tests
use the isolated `im.collie.app.imo` app and matching test APKs. Save physical
device checks for final validation.

Merging to the development branch does not sign or publish a release. A Beta
release is built by `build-release.yml` and published as an immutable prerelease;
the release engineering feed checks it against that build.
