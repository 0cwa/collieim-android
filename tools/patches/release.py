#!/usr/bin/env python3
"""Assemble a release from an unchanged Molly snapshot and explicit port fixes.

The historical forest is always proved against its official source first.
Upgrades use Git's deterministic three-way merge, followed by separately
reviewed resolution mailboxes. No AI, fuzzy matching, or patch regeneration
runs during a build.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tempfile

import forest
import git_utils
import verify_complete


METADATA_PATHS = (
    'tools/patches/', 'tools/native/', 'patches/molly/', 'patches/ports/',
    'molly-build.json', 'native-ringrtc.json', 'docs/PATCH-PIPELINE.md',
    '.github/workflows/android.yml', '.github/workflows/diffuse.yml',
    '.github/workflows/molly-patch-release.yml',
)

MERGE_OPTIONS = (
    '-c', 'merge.conflictStyle=merge', '-c', 'merge.renames=true',
    '-c', 'merge.renameLimit=7000', '-c', 'merge.directoryRenames=conflict',
    '-c', 'merge.renormalize=false', '-c', 'core.autocrlf=false',
    '-c', 'core.attributesFile=/dev/null', '-c', 'core.hooksPath=/dev/null',
)


def require_sha(value, label):
    if not isinstance(value, str) or not forest.SHA.fullmatch(value):
        raise forest.ForestError(f'{label} must be a full lowercase Git object ID')
    return value


def inside(root, value, label):
    """Resolve a plain repository-relative path without traversals or symlinks."""
    if not isinstance(value, str) or not value:
        raise forest.ForestError(f'{label} must be a nonempty repository-relative path')
    path = PurePosixPath(value)
    if path.is_absolute() or '..' in path.parts or '.' == value or '\\' in value:
        raise forest.ForestError(f'{label} is not a safe repository-relative path: {value!r}')
    result = Path(root) / value
    for component in (result, *result.parents):
        if component.is_symlink():
            raise forest.ForestError(f'{label} contains a symlink: {component}')
        if component == Path(root):
            break
    if not result.resolve().is_relative_to(Path(root).resolve()):
        raise forest.ForestError(f'{label} escapes the repository')
    return result


def load_config(path):
    data = json.loads(Path(path).read_text())
    if data.get('schema') != 1:
        raise forest.ForestError('unsupported molly-build.json schema')
    require_sha(data['signal']['commit'], 'Signal commit')
    require_sha(data['molly']['target'], 'Molly target')
    require_sha(data['molly']['tree'], 'Molly tree')
    if data.get('target_tree') is not None:
        require_sha(data['target_tree'], 'release target tree')
    return data


def check_source_boundary(repo, signal):
    """A new base edit must be reviewed explicitly, never silently excluded."""
    verify_complete.commit(repo, signal, 'Signal source')
    if git_utils.git(repo, 'merge-base', '--is-ancestor', signal, 'HEAD', check=False).returncode:
        raise forest.ForestError('the PR must descend from its pinned Signal source commit')
    changed = verify_complete.changed_paths(repo, signal, 'HEAD')
    unexpected = {
        path for path in changed
        if not any(os.fsdecode(path).startswith(allowed) if allowed.endswith('/')
                   else os.fsdecode(path) == allowed for allowed in METADATA_PATHS)
    }
    if unexpected:
        raise forest.ForestError('unaccounted edits to the tracked Signal base: '
                                + verify_complete.display_paths(unexpected))


def mechanical_merge(repo, base, molly, signal):
    """Merge pinned inputs with fixed MOLLY/SIGNAL labels and isolated Git rules.

    A temporary bare repository prevents local configuration and untracked
    attributes from changing merge behavior. Only the selected repository's
    object store is shared, so newly produced trees remain available to replay.
    Stable ref names keep unrelated commit-ID changes out of conflict hunks.
    """
    for value in (base, molly, signal):
        require_sha(value, 'merge input')
    env = forest.clean_git_env()
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull,
               GIT_ATTR_NOSYSTEM='1', GIT_NO_LAZY_FETCH='1', LC_ALL='C')
    for key in list(env):
        if key in ('GIT_CONFIG', 'GIT_CONFIG_PARAMETERS', 'GIT_CONFIG_COUNT', 'GIT_ATTR_SOURCE') \
                or key.startswith(('GIT_CONFIG_KEY_', 'GIT_CONFIG_VALUE_')):
            del env[key]
    objects = git_utils.git(repo, 'rev-parse', '--path-format=absolute', '--git-path',
                            'objects', env=env).stdout.strip()
    object_format = git_utils.git(repo, 'rev-parse', '--show-object-format', env=env).stdout.strip()
    with tempfile.TemporaryDirectory(prefix='molly-merge-') as temporary:
        git_utils.git(temporary, 'init', '--bare', '--quiet', '--template=',
                      f'--object-format={object_format}', env=env)
        env['GIT_OBJECT_DIRECTORY'] = objects

        def isolated_git(*args):
            result = subprocess.run(['git', '--git-dir', temporary, *args],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
            if result.returncode:
                raise forest.ForestError('isolated merge setup failed:\n'
                                        + result.stderr.decode(errors='replace'))
            return result.stdout

        for name, commit in (('MOLLY', molly), ('SIGNAL', signal)):
            isolated_git('update-ref', f'refs/heads/{name}', commit)
        # Git silently ignores an unavailable .gitattributes blob in a partial
        # clone. Require every pinned attribute file before accepting any merge
        # result; otherwise the same commits can yield different merge rules.
        entries = isolated_git('ls-tree', '-r', '-z', '--full-tree', 'MOLLY')
        for entry in entries.split(b'\0'):
            if not entry:
                continue
            metadata, path = entry.split(b'\t', 1)
            if path.rsplit(b'/', 1)[-1] == b'.gitattributes':
                blob = metadata.split()[2].decode('ascii')
                try:
                    isolated_git('cat-file', '-e', blob)
                except forest.ForestError as exc:
                    raise forest.ForestError('pinned merge attributes are unavailable: '
                                            f'{os.fsdecode(path)!r} ({blob}); materialize '
                                            'the complete pinned Molly source first') from exc
        # Bare repositories have no index/worktree attribute fallback. Select
        # the pinned first input explicitly; untracked attributes stay excluded.
        isolated_git('symbolic-ref', 'HEAD', 'refs/heads/MOLLY')
        env['GIT_ATTR_SOURCE'] = 'MOLLY'
        result = subprocess.run(
            ['git', '--git-dir', temporary, *MERGE_OPTIONS, 'merge-tree', '--write-tree',
             '--messages', '-Xfind-renames=50%', f'--merge-base={base}', 'MOLLY', 'SIGNAL'],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    if result.returncode not in (0, 1):
        raise forest.ForestError('deterministic merge failed:\n' + result.stderr.decode(errors='replace'))
    if result.stderr.strip():
        raise forest.ForestError('deterministic merge reported unexpected diagnostics:\n'
                                + result.stderr.decode(errors='replace'))
    output = result.stdout.decode()
    tree = output.splitlines()[0] if output else ''
    require_sha(tree, 'mechanical merge tree')
    # The first section after the tree contains unmerged index entries. Paths
    # with tabs/newlines use Git quoting; none of the pinned release paths do.
    conflicts = set()
    for line in output.splitlines()[1:]:
        if not line:
            break
        if '\t' not in line:
            raise forest.ForestError('unexpected merge-tree conflict entry')
        _, path = line.split('\t', 1)
        if path.startswith('"'):
            raise forest.ForestError('quoted conflict paths require an explicit parser update')
        conflicts.add(path)
    return tree, conflicts, output


def resolution_series(root, path):
    series = inside(root, path, 'resolution series')
    records = []
    for entry in forest.lines(series):
        patch = inside(series.parent, entry, 'resolution mailbox')
        if patch.suffix != '.patch' or not patch.is_file():
            raise forest.ForestError(f'missing resolution mailbox: {entry}')
        metadata = forest.mailbox(patch, entry)
        if len(metadata['source_commit_shas']) != 1:
            raise forest.ForestError(f'{entry}: a resolution must identify its source commit')
        records.append((patch, metadata))
    return records


def apply_resolutions(repo, worktree, tree, records):
    # A synthetic local commit materializes the mechanical result. It never
    # enters the source PR history; only its fully pinned tree matters.
    env = forest.clean_git_env()
    env.update(GIT_AUTHOR_NAME='Molly Patch Assembly',
               GIT_AUTHOR_EMAIL='patch-assembly@example.invalid',
               GIT_COMMITTER_NAME='Molly Patch Assembly',
               GIT_COMMITTER_EMAIL='patch-assembly@example.invalid',
               GIT_AUTHOR_DATE='2000-01-01T00:00:00Z',
               GIT_COMMITTER_DATE='2000-01-01T00:00:00Z')
    commit = git_utils.git(repo, '-c', 'commit.gpgSign=false', 'commit-tree', tree,
                          '-m', 'Materialize the pinned deterministic Molly/Signal merge',
                          env=env).stdout.strip()
    forest.add_worktree(repo, worktree, commit)
    changed = set()
    applied = []
    for patch, metadata in records:
        if hashlib.sha256(patch.read_bytes()).hexdigest() != metadata['sha256']:
            raise forest.ForestError(f'resolution changed after validation: {patch}')
        before = forest.git(worktree, 'rev-parse', 'HEAD')
        try:
            forest.git(worktree, 'am', '--no-3way', '--whitespace=nowarn',
                       '--committer-date-is-author-date', str(patch))
        except forest.ForestError as exc:
            raise forest.ForestError(f'{patch}: {exc}\nWorktree preserved at {worktree}') from exc
        after = forest.git(worktree, 'rev-parse', 'HEAD')
        changed.update(os.fsdecode(path) for path in
                       verify_complete.changed_paths(repo, before, after))
        applied.append(metadata)
    return changed, applied


def assemble(repo, config_path, worktree):
    repo = Path(repo).resolve()
    config = load_config(config_path)
    destination = forest.destination(repo, worktree)
    signal = config['signal']['commit']
    check_source_boundary(repo, signal)
    patches = inside(repo, config['molly']['patches'], 'Molly forest')
    data = forest.Forest(patches)
    identity = verify_complete.validate_identity(data, repo)
    if (identity['target'], identity['expected_tree']) != (
            config['molly']['target'], config['molly']['tree']):
        raise forest.ForestError('release configuration does not match the official Molly forest')
    port = config.get('port')
    if port is None:
        if signal != data.base or config.get('target_tree') != identity['expected_tree']:
            raise forest.ForestError('a historical build must use the exact original Signal and Molly trees')
        proof = verify_complete.verify(patches, repo, destination)
        return {'status': 'PASS', 'signal': signal, 'historical': proof,
                'tree': proof['expected_tree'], 'worktree': str(destination),
                'native': config.get('native')}

    # Prove the unchanged original patches before using their target as a merge
    # input. The target commit supplies stable labels; its tree was just rebuilt.
    proof = verify_complete.verify(patches, repo)
    expected_mechanical = require_sha(port['mechanical_tree'], 'mechanical tree pin')
    tree, conflicts, merge_output = mechanical_merge(repo, data.base, identity['target'], signal)
    if tree != expected_mechanical:
        raise forest.ForestError(f'mechanical merge changed: expected {expected_mechanical}, got {tree}; '
                                'inspect the Git version/merge inputs instead of regenerating patches')
    expected = config.get('target_tree')
    if expected is None:
        raise forest.ForestError(f'unadapted review checkpoint: {len(conflicts)} paths need explicit '
                                'port decisions; no buildable target has been approved')
    records = resolution_series(repo, port['series'])
    if not records and conflicts:
        raise forest.ForestError('conflicted merge has no explicit resolution mailboxes')
    touched, applied = apply_resolutions(repo, destination, tree, records)
    kept = port.get('keep_mechanical', {})
    if not isinstance(kept, dict) or any(not isinstance(why, str) or not why.strip()
                                         for why in kept.values()):
        raise forest.ForestError('keep_mechanical must map exact conflict paths to review reasons')
    if set(kept) - conflicts:
        raise forest.ForestError('keep_mechanical contains paths that did not conflict')
    unresolved = conflicts - touched - set(kept)
    if unresolved:
        raise forest.ForestError('unreviewed merge conflicts: ' + ', '.join(sorted(unresolved)))
    actual = forest.git(destination, 'rev-parse', 'HEAD^{tree}')
    changed_kept = set(kept) & {os.fsdecode(path) for path in
                              verify_complete.changed_paths(repo, tree, actual)}
    if changed_kept:
        raise forest.ForestError('keep_mechanical paths changed during resolution: '
                                + ', '.join(sorted(changed_kept)))
    if actual != expected:
        raise forest.ForestError(f'final tree mismatch: expected {expected}, got {actual}')
    verify_complete.clean_worktree(destination)
    return {'status': 'PASS', 'signal': signal, 'historical': proof,
            'mechanical_tree': tree, 'conflict_paths': len(conflicts),
            'keep_mechanical': kept, 'merge_output': merge_output,
            'resolutions': applied, 'tree': actual, 'worktree': str(destination),
            'native': config.get('native')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path('.'))
    parser.add_argument('--config', type=Path, default=Path('molly-build.json'))
    parser.add_argument('--worktree', type=Path, required=True)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    try:
        result = assemble(args.repo, args.config, args.worktree)
        code = 0
    except (forest.ForestError, git_utils.GitError, OSError, UnicodeError, ValueError, KeyError) as exc:
        result, code = {'status': 'FAIL', 'error': str(exc)}, 1
    serialized = json.dumps(result, indent=2) + '\n'
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(serialized)
    print(serialized, end='')
    return code


if __name__ == '__main__':
    sys.exit(main())
