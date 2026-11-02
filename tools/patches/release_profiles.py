"""Select immutable product build inputs without altering legacy profiles."""
import copy
import hashlib
import json
import re


NAMES = {'molly-minimal', 'molly-continued', 'molly-foss-continued',
         'collie', 'collie-foss'}
FOSS = {'molly-foss-continued', 'collie-foss'}


class ProfileError(ValueError):
    pass


def select(config, name):
    if name is None:
        return config, None
    if name not in NAMES:
        raise ProfileError('unknown release profile: ' + name)
    if name in {'molly-foss-continued', 'collie', 'collie-foss'}:
        raise ProfileError('fork profile selection requires fork checkpoint verification, which is not implemented yet')
    entry = config.get('profiles', {}).get(name)
    if not isinstance(entry, dict):
        raise ProfileError('release profile is not configured: ' + name)
    required = {'base_series', 'base_tree', 'fork_series', 'target_tree', 'tests', 'firebase_free'}
    if set(entry) != required:
        raise ProfileError('profile must specify exactly: ' + ', '.join(sorted(required)))
    for field in ('base_tree', 'target_tree'):
        if not isinstance(entry[field], str) or not re.fullmatch(r'[0-9a-f]{40}', entry[field]):
            raise ProfileError(field + ' must be a full lowercase tree ID')
    if not isinstance(entry['base_series'], str) or not entry['base_series']:
        raise ProfileError('base_series must be a nonempty path')
    fork = entry['fork_series']
    if not isinstance(fork, list) or any(not isinstance(x, str) or not x for x in fork) or len(set(fork)) != len(fork):
        raise ProfileError('fork_series must be an ordered list of distinct paths')
    if type(entry['firebase_free']) is not bool or entry['firebase_free'] != (name in FOSS):
        raise ProfileError('firebase_free does not match the product')
    if not isinstance(entry['tests'], list) or not entry['tests'] or any(not isinstance(x, str) or not x for x in entry['tests']):
        raise ProfileError('tests must be a nonempty list of Gradle arguments')
    if not config.get('port') or not isinstance(config.get('android'), dict):
        raise ProfileError('named profiles require a port and Android build settings')
    selected = copy.deepcopy(config)
    selected.pop('profiles', None)
    selected['port']['series'] = entry['base_series']
    selected['target_tree'] = entry['target_tree']
    selected['android']['tests'] = entry['tests']
    selected['_profile'] = {'name': name, 'base_tree': entry['base_tree'],
                            'fork_series': fork, 'firebase_free': entry['firebase_free']}
    fingerprint = hashlib.sha256(json.dumps(selected, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    selected['_profile']['fingerprint'] = fingerprint
    return selected, selected['_profile']
