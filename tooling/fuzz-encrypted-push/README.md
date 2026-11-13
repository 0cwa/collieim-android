# Encrypted push JVM checks

This isolated Gradle module compiles the production `EncryptedPush`, `ControlKeyId`, `EnrollmentFile`, and
`ControlFrame` Kotlin sources directly. It does not copy their implementations or make security decisions in test
stubs.

Run deterministic fixtures and saved crash inputs with:

```sh
./gradlew :fuzz-encrypted-push:test --no-daemon --no-configuration-cache --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g
```

Run the bounded Jazzer discovery target with:

```sh
./gradlew :fuzz-encrypted-push:jazzerFuzz --no-daemon --no-configuration-cache --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g
```

The incoming event parser and receiver `decide` function receive at most 4096 bytes, use a fixed clock and permit
protocol versions 1 through 3. The target checks accepted valid events, replay rejection, and unchanged state for
invalid events. It has a 60 second discovery limit. Six pinned v3 event fixtures are explicit initial seeds. Both
workflow jobs run in a container capped at 4 GiB memory with swap disabled and two CPUs; Gradle uses one worker and
a 1 GiB daemon heap, and each JVM test worker heap is capped at 1 GiB. Track
minimized regression crashes in `src/test/resources/im/molly/unifiedpush/receiver/EncryptedPushFuzzTestInputs/`; when
that directory is absent, Jazzer writes new crashes in the module working directory, which CI preserves as an artifact. The separate
`.cifuzz-corpus/` directory is disposable rolling discovery data, capped in CI to 512 files and 16 MiB.
Dispatching the workflow from a release tag replays that exact checked-out source revision and records its commit,
contract pin, JDK, engine version, command, and bounds in the uploaded run evidence. The workflow is advisory and does
not gate release publication.

`ControlFrame.build` constructs an outgoing authenticated control frame. Its deterministic fixed nonce test verifies
repeatable frame construction, header identity, and size for that nonce. It does not independently decrypt the
output, verify ciphertext or authentication-tag correctness, or test tamper rejection. The Android production sources
expose no incoming control-frame decryption seam, so this module makes no inbound decryption coverage claim.

The arbitrary-byte target uses the production `EncryptedPush.parse` result to choose its decision context
(`activeInstance` and `acceptedEpochs`) and leaves `identityMatches` at its production default of `true`. This checks
parser/decision consistency, malformed-input handling, and replay behavior; it is not an independent schema oracle or
enrollment-identity policy proof. State transition scenarios stay in the existing Android JUnit4 test suite and use
`EndpointUpdates` with its in-memory store.
