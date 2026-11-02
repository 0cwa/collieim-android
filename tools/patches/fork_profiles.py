"""Verify profile port prefixes and separately owned fork checkpoint layers."""
import hashlib
import json
from pathlib import Path

import forest
import git_utils
import release
import release_profiles
import verify_source_bundles


def port_prefix(repo, config_path, selection, records):
    """Verify the complete port chain, then select an exact layer boundary."""
    verify_source_bundles.verify(repo, config_path)
    series = release.inside(repo, release_profiles.PORT_SERIES, 'port series')
    provenance = json.loads((series.parent / 'provenance.json').read_text())
    layers = provenance['layers']
    flattened = [path for layer in layers for path in layer['patches']]
    if flattened != forest.lines(series):
        raise forest.ForestError('port series and provenance order differ')
    if len(flattened) != len(records):
        raise forest.ForestError('port series record count differs')
    for path, (_, metadata) in zip(flattened, records):
        if metadata['sha256'] != provenance['mailbox_sha256'][path]:
            raise forest.ForestError('port series file hash differs: ' + path)
    endpoints = [i for i, layer in enumerate(layers) if layer['id'] == selection['base_layer']]
    if len(endpoints) != 1:
        raise forest.ForestError('selected port boundary missing or duplicated')
    end = endpoints[0]
    layer = layers[end]
    if (layer['source_commit'], layer['after_tree']) != (selection['base_checkpoint'], selection['base_tree']):
        raise forest.ForestError('selected port checkpoint or tree differs')
    count = sum(len(layer['patches']) for layer in layers[:end + 1])
    return records[:count]


def fork_layers(repo, selection):
    """Import and verify the selected fork prefix anchored to the port checkpoint."""
    root = release.inside(repo, release_profiles.FORK_ROOT, 'fork root')
    provenance_path = release.inside(repo, release_profiles.FORK_ROOT + '/provenance.json', 'fork provenance')
    data = json.loads(provenance_path.read_text())
    if data['schema'] != 1 or data['source_url'] != 'https://github.com/tw-hx/Signal-Android':
        raise forest.ForestError('fork origin or schema differs')
    release.require_sha(data['source_head'], 'fork source head')
    if (data['base_commit'], data['base_tree']) != (selection['base_checkpoint'], selection['base_tree']):
        raise forest.ForestError('fork base checkpoint or tree differs')
    layers = data['layers']
    if not isinstance(layers, list) or [x['id'] for x in layers] != ['firebase-free-build', 'retention-controls']:
        raise forest.ForestError('fork layer order differs')
    selected = selection['fork_series']
    if [release_profiles.FORK_ROOT + '/' + x['series'] for x in layers[:len(selected)]] != selected:
        raise forest.ForestError('fork series differs from selected prefix')
    previous, tree = selection['base_checkpoint'], selection['base_tree']
    records = []
    for layer in layers[:len(selected)]:
        commit = release.require_sha(layer['source_commit'], 'fork source commit')
        original = release.require_sha(layer['upstream_commit'], 'fork upstream commit')
        if not isinstance(layer['upstream_author'], str) or not layer['upstream_author']:
            raise forest.ForestError('fork upstream author missing')
        if layer['id'] == 'firebase-free-build' and original != data['source_head']:
            raise forest.ForestError('Firebase origin does not match fork source head')
        if (layer['source_bundle_prerequisite'], layer['before_tree']) != (previous, tree):
            raise forest.ForestError('fork parent checkpoint or tree differs')
        series_path = release.inside(repo, release_profiles.FORK_ROOT + '/' + layer['series'], 'fork series')
        entries = forest.lines(series_path)
        mailbox = release.inside(repo, release_profiles.FORK_ROOT + '/' + layer['mailbox'], 'fork mailbox')
        if len(entries) != 1 or release.inside(series_path.parent, entries[0], 'fork series mailbox') != mailbox:
            raise forest.ForestError('fork series must select its one declared mailbox')
        content = mailbox.read_bytes()
        if len(content) != layer['mailbox_bytes'] or hashlib.sha256(content).hexdigest() != layer['mailbox_sha256']:
            raise forest.ForestError('fork mailbox bytes or hash differ')
        metadata = forest.mailbox(mailbox, layer['mailbox'])
        if metadata['source_commit_shas'] != [commit] or metadata['mailbox_commit_sha'] != commit:
            raise forest.ForestError('fork mailbox source commit differs')
        ref = layer['source_bundle_ref']
        if not isinstance(ref, str) or not ref.startswith('refs/heads/source-checkpoint/'):
            raise forest.ForestError('fork checkpoint ref is invalid')
        verify_source_bundles.import_bundle(repo, Path(repo), root, layer['source_bundle'],
            layer['source_bundle_sha256'], layer['source_bundle_bytes'], ref, commit, [previous])
        actual_tree, parents = verify_source_bundles.commit_info(repo, commit)
        if parents != [previous] or actual_tree != layer['after_tree']:
            raise forest.ForestError('fork source commit parent or tree differs')
        author = git_utils.git(repo, 'show', '-s', '--format=%an <%ae>', commit).stdout.strip()
        if author != layer['upstream_author']:
            raise forest.ForestError('fork adapted commit author differs')
        available = git_utils.git(repo, 'cat-file', '-e', original + '^{commit}', check=False)
        if available.returncode == 0:
            author = git_utils.git(repo, 'show', '-s', '--format=%an <%ae>', original).stdout.strip()
            if author != layer['upstream_author']:
                raise forest.ForestError('fork upstream commit author differs')
        records.append((mailbox, metadata, layer['after_tree']))
        previous, tree = commit, actual_tree
    if tree != selection['target_tree']:
        raise forest.ForestError('fork final source tree differs from profile')
    return records
