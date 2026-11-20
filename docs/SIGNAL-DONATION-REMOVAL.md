# Signal donation removal

The removal list in [signal-donation-removals.json](../tooling/upstream/signal-donation-removals.json) names the exact files and their purpose. The remaining interfaces keep old job payloads and stored payment fields readable. `:lib:donations` still provides shared backup permits and payment record types.

## When updating Signal

Fetch and verify the two upstream tags as described in the [development cycle](https://github.com/0cwa/collie-release-engineering/blob/main/docs/DEVELOPMENT-CYCLE.md). Use the ordinary tag-to-tag port on a clean topic branch:

```sh
python3 tooling/upstream/apply-signal-upgrade.py signal-vOLD signal-vNEW
```

The script applies the binary, rename-aware tag diff with `git apply --3way --index`. On the reviewed donation paths it skips upstream edits to already removed files and re-applies the listed deletions. An upstream rename, type change, or addition involving a listed path stops before applying the diff. A conflict anywhere else stops the port before the deletion pass. Review new donation code and shared backup interfaces explicitly when updating the list.

## Patch evidence

The [patch explorer record](../tooling/site/receipt/subjects/signal-donation-removal.json) binds this change and the literal deletion list to the source tree. Select it when reviewing a release tag that contains the removal and run the normal receipt freeze. The older reviewed release keeps its existing source pins. The [unreleased Project Log entry](../app/src/main/assets/collie-log/unreleased/210.txt) records the change for a future release.

The API 35 navigation job verifies the built APK before installation. It checks the removed checkout classes and resources and confirms the retained backup classes. The output digest identifies the APK checked.
