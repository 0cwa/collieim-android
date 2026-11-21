# Signal donation removal

The removal list in [signal-donation-removals.json](../tooling/upstream/signal-donation-removals.json) names the exact files and their purpose. The remaining interfaces keep old job payloads and stored payment fields readable. `:lib:donations` still provides shared backup permits and payment record types.

## When updating Signal or Molly

Merge the upstream release on a clean topic branch:

```sh
python3 tooling/upstream/merge-upstream.py signal v8.37.0   # git merge of the Signal tag
python3 tooling/upstream/merge-upstream.py molly main       # git merge of the Molly branch
```

The script fetches the tag or branch and runs `git merge --no-commit`. Where upstream edited a file on the removal list, the file stays deleted. Every other conflict, including an upstream rename of a listed file, is left for review, and an upstream addition of a listed path is flagged. The prepared message lists those conflicts and ends with the trailers:

```text
Layer: upgrade
Upstream: signalapp/Signal-Android v8.37.0
```

A Molly merge declares `Upstream: mollyim/mollyim-android <branch>@<full-sha>`. Resolve the conflicts and run `git commit`; the merge commit is signed, while the upstream commits stay unsigned. `--join` records a release Collie already ported as a diff without taking its changes. Review new donation code and shared backup interfaces explicitly when updating the list. The list holds no string resources; donation strings stay as Molly has them.

## Patch evidence

The [patch explorer record](../tooling/site/receipt/subjects/signal-donation-removal.json) binds this change and the literal deletion list to the source tree. Select it when reviewing a release tag that contains the removal and run the normal receipt freeze. The older reviewed release keeps its existing source pins. The [unreleased Project Log entry](../app/src/main/assets/collie-log/unreleased/210.txt) records the change for a future release.

The API 35 navigation job verifies the built APK before installation. It checks the removed checkout classes and resources and confirms the retained backup classes. The output digest identifies the APK checked.
