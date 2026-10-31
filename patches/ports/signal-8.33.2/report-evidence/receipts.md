# Report action receipts

These records describe the actual tests, not independent reruns on each report
profile. Patch replay proves the recorded file input and output blobs; it does
not prove an entire tested source tree was identical to a report profile.

## fmt-01

`app/src/main/res/layout/audio_view.xml`: `571410be668d8b24234e3b91dc0b98bfcbc8e1eb`
→ `698b435c04fd0a12181856f1a273051d28d8effb`. The XML event comparison
reported `XML_EVENTS_EQUAL`. The tested file was in the PR4-derived source
context at tree `70f2eba69967c4a3ae82dd9204999feb1bd70f9b`, with the tested
candidate applied; this does not assert a complete identical PR3 input tree.

The recorded command was `agent-heavy -- ./gradlew --no-daemon --no-parallel
--no-configuration-cache --max-workers=1 --dependency-verification=strict
--init-script <test-worker-heap.init.gradle> -Pkotlin.compiler.execution.strategy=in-process
'-Dorg.gradle.jvmargs=-Xmx5g -Xms256m -XX:MaxMetaspaceSize=1g -Dcom.android.tools.r8.threadCount=1 -XX:+UnlockExperimentalVMOptions -XX:hashCode=3'
:app:processProdWebsiteDebugResources`. The log shows the resource task
executed and `BUILD SUCCESSFUL`, exit 0. JDK 21 and the configured Android SDK
were used. Source records: `xml-format-gates.tsv`,
`xml-format-hunk-01-xml.log`, and `xml-format-hunk-01-resource.log` in task
scratch. A passing removal check does not establish another hunk's necessity.

## lex-01

`app/src/main/res/values-af/strings.xml`: baseline blob
`834085e2b39349c74ee9bb338202b8d222fdc1a0`; restored header blob
`c1457e149094d35c0973c2aa6e00d3d0d766b667`. The recorded file-scoped commands
were `aapt2 compile -o <output-dir> app/src/main/res/values-af/strings.xml`,
with AAPT2 `9.4.0-15978811-linux`; both inputs exited 0 with empty output.
Source record: `XML-LEXICAL-CHECK.tsv` in task scratch. The same file blobs
occur in both report profiles; this does not claim full resource linking.
