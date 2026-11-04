#!/usr/bin/env python3
"""Create a verified draft release, or explicitly publish an already verified draft."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile

import release_artifacts as a


def command(args, dry=False, **kwargs):
    args = [str(x) for x in args]
    print('+ ' + shlex.join(args), flush=True)
    if not dry:
        return subprocess.run(args, check=True, **kwargs)
    return None


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=('draft', 'publish'))
    p.add_argument('--repo', required=True)
    p.add_argument('--tag', required=True)
    p.add_argument('--approved-commit', required=True)
    p.add_argument('--final-manifest', type=Path, required=True)
    p.add_argument('--final-manifest-sha256', required=True)
    p.add_argument('--provenance', type=Path, required=True)
    p.add_argument('--notes', type=Path, required=True)
    p.add_argument('--scratch', type=Path, required=True)
    p.add_argument('--prerelease', action='store_true', help='create and verify a testing prerelease')
    p.add_argument('--dry-run', action='store_true')
    args = p.parse_args()
    try:
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repo):
            raise ValueError('invalid GitHub repository')
        if not re.fullmatch(r'v[A-Za-z0-9][A-Za-z0-9._-]{0,79}', args.tag) or '..' in args.tag:
            raise ValueError('invalid release tag')
        if not re.fullmatch(r'[0-9a-f]{40}', args.approved_commit):
            raise ValueError('approved commit must be a full SHA-1')
        if not args.scratch.is_dir():
            raise ValueError('scratch directory must already exist')
        with tempfile.TemporaryDirectory(prefix='release-publish-', dir=args.scratch) as tmp:
            tmp = Path(tmp)
            inputs = tmp / 'inputs'
            inputs.mkdir(mode=0o700)
            final = inputs / 'final-manifest.json'
            a.snapshot(args.final_manifest, final)
            if a.sha256(final) != args.final_manifest_sha256:
                raise ValueError('final manifest differs from independently approved digest')
            manifest = json.loads(final.read_text())
            if manifest.get('schema') != 1 or manifest.get('rehearsal') is not False or manifest.get('commit') != args.approved_commit:
                raise ValueError('final manifest is not a real release of the approved commit')
            entries = manifest['apks']
            if len(entries) != 5 or {x['profile'] for x in entries} != a.PROFILES:
                raise ValueError('final manifest must contain all five profiles')
            assets = []
            expected = {}
            for entry in entries:
                if entry['file'] != entry['profile'] + '.apk':
                    raise ValueError('invalid APK filename')
                path = inputs / entry['file']
                a.snapshot(args.final_manifest.parent / entry['file'], path)
                expected[path.name] = entry['signed_sha256']
                if a.sha256(path) != expected[path.name]:
                    raise ValueError('signed APK hash differs: ' + entry['file'])
                assets.append(path)
            provenance = inputs / 'provenance.json'
            notes = inputs / 'release-notes.md'
            a.snapshot(args.provenance, provenance)
            a.snapshot(args.notes, notes)
            if a.sha256(provenance) != manifest.get('unsigned_manifest_sha256'):
                raise ValueError('provenance does not match the unsigned manifest used for signing')
            assets += [final, provenance, notes]
            expected.update({final.name: args.final_manifest_sha256,
                             provenance.name: manifest['unsigned_manifest_sha256'],
                             notes.name: a.sha256(notes)})
            sums = inputs / 'SHA256SUMS'
            sums.write_text(''.join(expected[path.name] + '  ' + path.name + '\n' for path in assets))
            sums.chmod(0o600)
            expected[sums.name] = a.sha256(sums)
            assets.append(sums)
            if args.action == 'draft':
                command(['gh', 'api', '--method', 'POST', f'repos/{args.repo}/git/refs',
                         '-f', 'ref=refs/tags/' + args.tag, '-f', 'sha=' + args.approved_commit], args.dry_run)
                command(['gh', 'release', 'create', args.tag, '--repo', args.repo, '--verify-tag', '--draft',
                         '--title', args.tag, '--notes-file', notes,
                         *(['--prerelease', '--latest=false'] if args.prerelease else []), *assets], args.dry_run)
            # Re-download and check on both commands, including immediately before publishing.
            if not args.dry_run:
                ref = command(['gh', 'api', f'repos/{args.repo}/git/ref/tags/{args.tag}'],
                              stdout=subprocess.PIPE, text=True)
                obj = json.loads(ref.stdout)['object']
                if obj.get('type') != 'commit' or obj.get('sha') != args.approved_commit:
                    raise ValueError('remote tag does not point directly at approved commit')
                info = command(['gh', 'release', 'view', args.tag, '--repo', args.repo, '--json', 'isDraft,isPrerelease,assets'],
                               stdout=subprocess.PIPE, text=True)
                release = json.loads(info.stdout)
                if (not release['isDraft'] or release['isPrerelease'] is not args.prerelease
                        or {x['name'] for x in release['assets']} != {x.name for x in assets}):
                    raise ValueError('draft state, prerelease setting or exact asset list differs')
            downloads = tmp / 'downloaded'
            downloads.mkdir()
            command(['gh', 'release', 'download', args.tag, '--repo', args.repo, '--dir', downloads], args.dry_run)
            if not args.dry_run:
                for path in assets:
                    if a.sha256(downloads / path.name) != expected[path.name]:
                        raise ValueError('downloaded asset hash differs: ' + path.name)
            if args.action == 'publish':
                command(['gh', 'release', 'edit', args.tag, '--repo', args.repo, '--draft=false'], args.dry_run)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        print('release publishing failed:', exc, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
