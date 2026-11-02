"""Select immutable product build inputs without altering legacy profiles."""
import copy
import hashlib
import json
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


def select(config, name):
    if name is None:
        return config, None
    if name not in NAMES:
        raise ProfileError('unknown release profile: ' + name)
    entry = config.get('profiles', {}).get(name)
    if not isinstance(entry, dict):
        raise ProfileError('release profile is not configured: ' + name)
    required = {'base_series', 'base_checkpoint', 'base_tree', 'fork_series', 'target_tree', 'tests', 'firebase_free'}
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
    if not isinstance(entry['tests'], list) or not entry['tests'] or any(not isinstance(x, str) or not x for x in entry['tests']):
        raise ProfileError('tests must be a nonempty list of Gradle arguments')
    if not config.get('port') or not isinstance(config.get('android'), dict):
        raise ProfileError('named profiles require a port and Android build settings')
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
    selected['android']['tests'] = entry['tests']
    selected['_profile'] = {'name': name, 'base_checkpoint': entry['base_checkpoint'],
                            'base_layer': boundary, 'base_tree': entry['base_tree'], 'target_tree': entry['target_tree'],
                            'fork_series': fork, 'firebase_free': entry['firebase_free']}
    if fork:
        selected['_profile']['fork_source'] = copy.deepcopy(fork_source)
        selected['_profile']['signal_commit'] = config['signal']['commit']
    fingerprint = hashlib.sha256(json.dumps(selected, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    selected['_profile']['fingerprint'] = fingerprint
    return selected, selected['_profile']
