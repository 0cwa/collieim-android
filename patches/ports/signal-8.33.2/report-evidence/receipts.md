# Report action receipts

These records describe the actual checks, not independent reruns on each
report profile. Replay proves recorded file input and output blobs. It does not
prove the full tested source tree was identical to a report profile.

## Formatting checks

Sixteen changes were checked in a PR4-derived source context at tree
`70f2eba69967c4a3ae82dd9204999feb1bd70f9b` with the tested candidate actions
applied; the complete tested tree is not pinned. This is not a resource-link
run on PR3. Each associated XML-event log records `XML_EVENTS_EQUAL`. Each
associated resource log shows `:app:processProdWebsiteDebugResources` executed
and `BUILD SUCCESSFUL`, exit 0. The file blob identities and sequence are in
report metadata and the versioned action patches.

The command used JDK 21, the configured Android SDK, and `agent-heavy --
./gradlew --no-daemon --no-parallel --no-configuration-cache --max-workers=1
--dependency-verification=strict --init-script <test-worker-heap.init.gradle>
-Pkotlin.compiler.execution.strategy=in-process
'-Dorg.gradle.jvmargs=-Xmx5g -Xms256m -XX:MaxMetaspaceSize=1g -Dcom.android.tools.r8.threadCount=1 -XX:+UnlockExperimentalVMOptions -XX:hashCode=3'
:app:processProdWebsiteDebugResources`. Checks 15 and 16 used `--rerun`; their
original resource logs showed `UP-TO-DATE` and are superseded by the forced
executions. Check 15 was tested with change 16 reversed; check 16 was then
reapplied and checked separately. Source records in task scratch are
`xml-format-gates.tsv`, `xml-format-gates-notes.md`, and the numbered XML and
resource logs named by the metadata. Passing removal checks do not establish
necessity of any current hunk.

## Lexical checks

Seventy baseline files and 74 sequential restorations were individually
compiled using `aapt2 compile -o <output-dir> <relative-resource-path>` with
AAPT2 `9.4.0-15978811-linux`. Each recorded command exited 0 with empty output.
The inputs are the file blobs in the metadata; the baseline and final file
blobs match both report profiles. These are file-scoped compilation checks,
not full resource-link checks. The source record in task scratch is
`XML-LEXICAL-CHECK.tsv`, whose phase, hunk name, path and input blob identify
each command. The intermediate file blobs were reconstructed from the exact
recorded transformations and verified against that record; the versioned
patches make their replay independent of local dangling objects.

## Whole-file deletion inventory

The following tree-membership check classifies whole-file deletions from mechanical tree `759ef1c309d93aa7e4ac7217b144c04545562f11` to both PR3 source tree `129bd88f3f04dfb6b80a93b96ec026f6a5cd3010` and PR4 source tree `2b0ba46b23fd27974b8cefbd36e0d91791b525bd`. Inputs are old Signal `c47a74ba0961903eeaeda6d22347a990056c3e83`, new Signal `6e9dddc01f1b26e608ac683807c51e139c141ed2`, and Molly `0e202dda90785771ff8c87526eaaf30c655f54f3`. After running `python3 tools/patches/prepare_port_report.py` on both stack 3 and stack 4 so both target trees are available in the shared object store, reproduce the inventory and classification check from the repository root:

```sh
python3 - <<'PY'
import collections, json, subprocess
from pathlib import Path

def git(*args):
    return subprocess.check_output(['git', *args])
def paths(ref):
    return set(filter(None, git('ls-tree', '-r', '-z', '--name-only', ref).split(b'\0')))
old, new, molly, mechanical = map(paths, (
    'c47a74ba0961903eeaeda6d22347a990056c3e83',
    '6e9dddc01f1b26e608ac683807c51e139c141ed2',
    '0e202dda90785771ff8c87526eaaf30c655f54f3',
    '759ef1c309d93aa7e4ac7217b144c04545562f11'))
targets = [paths(x) for x in ('129bd88f3f04dfb6b80a93b96ec026f6a5cd3010',
                              '2b0ba46b23fd27974b8cefbd36e0d91791b525bd')]
deleted = mechanical - targets[0]
assert deleted == mechanical - targets[1]
annotations = json.loads(Path('patches/ports/signal-8.33.2/report-metadata.json').read_text())['deletions']
assert deleted == {p.encode() for p in annotations}
absent_roots = (b'demo/media-keyboard/', b'demo/list-detail/', b'demo/device-transfer/', b'lib/billing/')
assert all(not any(p.startswith(root) for p in target) for root in absent_roots for target in targets)
counts = collections.Counter()
for p in deleted:
    if p in old and p not in new:
        cls = 'Signal path removed'
    elif p in old and p in new and p not in molly:
        cls = 'Both Signal trees; absent in Molly'
    elif p not in old and p in new and p not in molly:
        cls = 'New Signal path under absent root' if p.startswith(absent_roots) else 'New Signal path under retained root'
    else:
        assert p in molly
        cls = 'Molly-present additional path'
    if cls == 'New Signal path under retained root':
        root = b'/'.join(p.split(b'/')[:2]) + b'/'
        assert all(any(q.startswith(root) for q in target) for target in targets)
    assert annotations[p.decode()]['classification'] == cls
    counts[cls] += 1
print(dict(sorted(counts.items())))
raw = git('diff', '--no-renames', '--numstat', '-z', '759ef1c309d93aa7e4ac7217b144c04545562f11', '129bd88f3f04dfb6b80a93b96ec026f6a5cd3010')
rows = [r.split(b'\t', 2) for r in raw.split(b'\0') if r]
selected = [(a, d, p) for a, d, p in rows if p in deleted]
print('paths, text, binary, deleted lines:', len(selected), sum(a != b'-' for a, _, _ in selected), sum(a == b'-' for a, _, _ in selected), sum(int(d) for a, d, _ in selected if a != b'-'))
raw = git('diff', '--no-renames', '--raw', '--no-abbrev', '-z', '759ef1c309d93aa7e4ac7217b144c04545562f11', '129bd88f3f04dfb6b80a93b96ec026f6a5cd3010').split(b'\0')
print('old modes:', dict(collections.Counter(raw[i].split()[0][1:].decode() for i in range(0, len(raw)-1, 2) if raw[i+1] in deleted)))
PY
```

Observed output: `Both Signal trees; absent in Molly: 250`, `Molly-present additional path: 6`, `New Signal path under absent root: 85`, `New Signal path under retained root: 321`, `Signal path removed: 111`; 773 paths, 745 text, 28 binary, 45,285 deleted text lines, all old mode `100644`. The four absent roots contain no target paths; retained roots have surviving target paths. The category predicates classify paths, not why they disappeared. Molly path absence does not prove an intentional exclusion; old Signal path removal does not prove behavior preservation; a retained root does not establish an individual file's intent. The six Molly-present paths include tests and possible relocated files, whose full behavior remains unverified. Screen lock, proxy, and shake-report paths merit further caller and policy checks; this inventory does not prove runtime equivalence or deletion necessity. Partial-file exclusions remain unmeasured.
