# Git-only source inspection

Use a clean Git environment with replace refs and grafts disabled. Review the
exact Signal commit `a74583b01e260944f699c4da03ba4e8ce04de32b`, generated endpoint G,
and integration I from the externally approved source receipt. Do not substitute
GitHub's synthetic pull-request merge. `git merge-base --is-ancestor SIGNAL G`
checks upstream ancestry; `git diff SIGNAL G` reviews every changed path.
`git diff OLD_MAIN I` exposes the complete cutover. `git rev-parse G^{tree}
I^{tree}` must print identical complete trees, and `git show -s --format=%P I`
must name OLD_MAIN and G, in that order. Clear GIT_* overrides and reject
.git/info/grafts, refs/replace, alternates and shallow history before checking.

Reconstruction lives in the separately reviewed collie-release-engineering
recipe. Its receipt records inputs/layers and final trees after G exists; G does
not contain its own hash. Source inspection is not signed provenance or proof
that native binaries were independently rebuilt. Historical RC1's tag is retained
unchanged; this source checkpoint is not retroactively its build input.

Contribute normal application changes here. Maintainers export a full binary
Git delta, commit it as an engineering layer, regenerate and compare the whole
reviewed tree before release (source/PUBLICATION.md in engineering). The two
representations may not silently diverge.
