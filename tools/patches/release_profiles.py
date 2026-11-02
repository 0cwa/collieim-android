"""Select immutable product build inputs without altering legacy profiles."""
import copy
import hashlib
import json
from pathlib import Path
import re


NAMES = {'molly-minimal', 'molly-continued', 'molly-foss-continued',
         'collie', 'collie-foss'}
FOSS = {'molly-foss-continued', 'collie-foss'}
PORT_SERIES = 'patches/ports/signal-8.33.2/series'
FORK_ROOT = 'patches/forks/signal-foss'
FIREBASE = FORK_ROOT + '/firebase-free-build/series'
RETENTION = FORK_ROOT + '/retention-controls/series'
POLICY = {
    'molly-minimal': ('followups/backup-notification-id-upgrade', [], False),
    'molly-continued': ('followups/donation-completion-copy', [], False),
    'molly-foss-continued': ('followups/donation-completion-copy', [FIREBASE], True),
    'collie': ('followups/donation-completion-copy', [FIREBASE, RETENTION], False),
    'collie-foss': ('followups/donation-completion-copy', [FIREBASE, RETENTION], True),
}



class ProfileError(ValueError):
    pass


TEST_SOURCE = re.compile(r'^(?P<module>.+?)/src/test/(?:java|kotlin)/(?P<name>.+)\.(?:kt|java)$')
OTHER_TEST_SOURCE = re.compile(r'(^|/)src/(androidTest|testFixtures|screenshotTest\w*)/')


def layer_test_classes(repo, series):
    """Test classes a fork layer adds or changes, as (module, class), in mailbox order."""
    root = Path(repo) / FORK_ROOT
    provenance = json.loads((root / 'provenance.json').read_text())
    layers = [x for x in provenance['layers'] if FORK_ROOT + '/' + x['series'] == series]
    if len(layers) != 1:
        raise ProfileError('fork series has no single provenance layer: ' + series)
    text = (root / layers[0]['mailbox']).read_text(errors='surrogateescape')
    found = []
    for block in re.split(r'^(?=diff --git )', text, flags=re.M)[1:]:
        header = re.match(r'diff --git a/(\S+) b/(\S+)\n', block)
        if header is None:
            raise ProfileError('unreadable fork mailbox header in ' + series)
        path = header.group(2)
        if re.search(r'^deleted file mode ', block.split('\n@@', 1)[0], flags=re.M):
            continue
        match = TEST_SOURCE.match(path)
        if match:
            found.append((match['module'], match['name'].replace('/', '.')))
        elif OTHER_TEST_SOURCE.search(path):
            raise ProfileError('no unit test task rule for fork test source: ' + path)
    return found


def derived_tests(repo, base, fork_series):
    """Base Gradle test arguments plus the test classes each selected fork layer adds or changes.

    A class joins the filters of its module's existing test task. A task that already runs
    unfiltered keeps running everything; a module without a test task is an error.
    """
    groups, i = [], 0
    while i < len(base):
        if base[i] == '--tests':
            if not groups or i + 1 >= len(base):
                raise ProfileError('test filters must follow a Gradle task')
            groups[-1][1].append(base[i + 1]); i += 2
        else:
            groups.append([base[i], []]); i += 1
    for series in fork_series:
        for module, name in layer_test_classes(repo, series):
            prefix = ':' + module.replace('/', ':') + ':'
            tasks = [g for g in groups if g[0].startswith(prefix)]
            if len(tasks) != 1:
                raise ProfileError(f'fork test {name} needs exactly one {prefix} test task in android.tests')
            if tasks[0][1] and name not in tasks[0][1]:
                tasks[0][1].append(name)
    return [arg for task, filters in groups for arg in [task] + [x for f in filters for x in ('--tests', f)]]


def select(config, name, repo='.'):
    if name is None:
        return config, None
    if name not in NAMES:
        raise ProfileError('unknown release profile: ' + name)
    entry = config.get('profiles', {}).get(name)
    if not isinstance(entry, dict):
        raise ProfileError('release profile is not configured: ' + name)
    required = {'base_series', 'base_checkpoint', 'base_tree', 'fork_series', 'target_tree', 'firebase_free'}
    if set(entry) != required:
        raise ProfileError('profile must specify exactly: ' + ', '.join(sorted(required)))
    for field in ('base_checkpoint', 'base_tree', 'target_tree'):
        if not isinstance(entry[field], str) or not re.fullmatch(r'[0-9a-f]{40}', entry[field]):
            raise ProfileError(field + ' must be a full lowercase tree ID')
    boundary, expected_fork, expected_free = POLICY[name]
    if entry['base_series'] != PORT_SERIES:
        raise ProfileError('base_series does not match the port series')
    fork = entry['fork_series']
    if not isinstance(fork, list) or any(not isinstance(x, str) or not x for x in fork) or len(set(fork)) != len(fork):
        raise ProfileError('fork_series must be an ordered list of distinct paths')
    if fork != expected_fork:
        raise ProfileError('fork_series does not match the product composition')
    if type(entry['firebase_free']) is not bool or entry['firebase_free'] != expected_free:
        raise ProfileError('firebase_free does not match the product')
    if not config.get('port') or not isinstance(config.get('android'), dict):
        raise ProfileError('named profiles require a port and Android build settings')
    base_tests = config['android'].get('tests')
    if not isinstance(base_tests, list) or not base_tests or any(not isinstance(x, str) or not x for x in base_tests):
        raise ProfileError('android.tests must be a nonempty list of Gradle arguments')
    if fork:
        fork_source = config.get('fork_source')
        if not isinstance(fork_source, dict) or set(fork_source) != {'url', 'head'}:
            raise ProfileError('fork profiles require a fork_source URL and head pin')
        if fork_source['url'] != 'https://github.com/tw-hx/Signal-Android' or not isinstance(fork_source['head'], str) or not re.fullmatch(r'[0-9a-f]{40}', fork_source['head']):
            raise ProfileError('invalid fork_source URL or head')
        if not isinstance(config.get('signal', {}).get('commit'), str) or not re.fullmatch(r'[0-9a-f]{40}', config['signal']['commit']):
            raise ProfileError('fork profiles require a pinned Signal commit')
    selected = copy.deepcopy(config)
    selected.pop('profiles', None)
    selected['port']['series'] = entry['base_series']
    selected['target_tree'] = entry['target_tree']
    selected['android']['tests'] = derived_tests(repo, base_tests, fork)
    selected['_profile'] = {'name': name, 'base_checkpoint': entry['base_checkpoint'],
                            'base_layer': boundary, 'base_tree': entry['base_tree'], 'target_tree': entry['target_tree'],
                            'fork_series': fork, 'firebase_free': entry['firebase_free']}
    if fork:
        selected['_profile']['fork_source'] = copy.deepcopy(fork_source)
        selected['_profile']['signal_commit'] = config['signal']['commit']
    fingerprint = hashlib.sha256(json.dumps(selected, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    selected['_profile']['fingerprint'] = fingerprint
    return selected, selected['_profile']
