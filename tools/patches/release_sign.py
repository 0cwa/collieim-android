#!/usr/bin/env python3
"""Sign an independently authenticated release manifest and its five APKs offline."""
import argparse
import getpass
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

import release_artifacts as artifacts


def run(args, **kwargs):
    return subprocess.run([str(x) for x in args], check=True, **kwargs)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--manifest-sha256', required=True, help='digest authenticated independently from the downloaded artifact')
    p.add_argument('--approved-commit', required=True)
    p.add_argument('--approved-run', required=True)
    p.add_argument('--certificate-sha256', required=True)
    p.add_argument('--apks', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--build-tools', type=Path, required=True)
    p.add_argument('--attestation-repo', help='verify with gh; supply a trusted GH repository')
    p.add_argument('--rehearse', action='store_true')
    p.add_argument('--scratch', type=Path, required=True, help='existing private directory for temporary files')
    args = p.parse_args()
    try:
        if not args.scratch.is_dir():
            raise ValueError('--scratch must be an existing private directory')
        if args.output.exists():
            raise ValueError('output directory already exists')
        with tempfile.TemporaryDirectory(prefix='release-sign-', dir=args.scratch) as temp:
            temp = Path(temp)
            snapshot_dir = temp / 'inputs'
            snapshot_dir.mkdir(mode=0o700)
            manifest = snapshot_dir / 'manifest.json'
            artifacts.snapshot(args.manifest, manifest)
            if artifacts.sha256(manifest) != args.manifest_sha256:
                raise ValueError('manifest differs from independently authenticated SHA-256')
            m = json.loads(manifest.read_text())
            entries = artifacts.validate_manifest(m, args.approved_commit, args.approved_run, args.certificate_sha256)
            if args.build_tools.name != m.get('build_tools') or m.get('build_tools') != m.get('input_pins', {}).get('android', {}).get('build_tools'):
                raise ValueError('build-tools directory does not match manifest pin')
            if args.attestation_repo:
                run(['gh', 'attestation', 'verify', manifest, '--repo', args.attestation_repo,
                     '--source-digest', 'sha1:' + args.approved_commit])
            for e in entries:
                apk = snapshot_dir / e['file']
                artifacts.snapshot(args.apks / e['file'], apk)
                if artifacts.sha256(apk) != e['sha256']:
                    raise ValueError('unsigned APK hash mismatch: ' + e['file'])
                artifacts.check_unsigned(apk)
                artifacts.check_metadata(args.build_tools / 'aapt2', apk, m['version_code'], m['version_name'])
            env = os.environ.copy()
            if args.rehearse:
                keystore = temp / 'rehearsal.p12'
                env['MOLLY_KEYSTORE_PASSWORD'] = 'rehearsal-only-password'
                env['MOLLY_KEY_PASSWORD'] = env['MOLLY_KEYSTORE_PASSWORD']
                alias = 'rehearsal'
                run(['keytool', '-genkeypair', '-keystore', keystore, '-storetype', 'PKCS12',
                     '-storepass:env', 'MOLLY_KEYSTORE_PASSWORD', '-keypass:env', 'MOLLY_KEY_PASSWORD',
                     '-alias', alias, '-keyalg', 'RSA', '-keysize', '3072', '-validity', '7',
                     '-dname', 'CN=Disposable rehearsal'], env=env, stdout=subprocess.DEVNULL)
            else:
                keystore = Path(os.environ['MOLLY_KEYSTORE'])
                alias = os.environ['MOLLY_KEY_ALIAS']
                env['MOLLY_KEYSTORE_PASSWORD'] = os.environ.get('MOLLY_KEYSTORE_PASSWORD') or getpass.getpass('Keystore password: ')
                env['MOLLY_KEY_PASSWORD'] = os.environ.get('MOLLY_KEY_PASSWORD') or getpass.getpass('Key password: ')
            args.output.mkdir(parents=True)
            signed = []
            for e in entries:
                apk = snapshot_dir / e['file']
                aligned = temp / e['file']
                # Always align a fresh copy so the signed bytes are exactly those checked.
                run([args.build_tools / 'zipalign', '-f', '-p', '4', apk, aligned])
                run([args.build_tools / 'zipalign', '-c', '-p', '4', aligned])
                dest = args.output / e['file']
                run([args.build_tools / 'apksigner', 'sign', '--ks', keystore, '--ks-key-alias', alias,
                     '--ks-pass', 'env:MOLLY_KEYSTORE_PASSWORD', '--key-pass', 'env:MOLLY_KEY_PASSWORD',
                     '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true', '--out', dest, aligned], env=env)
                run([args.build_tools / 'zipalign', '-c', '-p', '4', dest])
                result = run([args.build_tools / 'apksigner', 'verify', '--verbose', '--print-certs', dest],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True).stdout
                print(result, end='')
                digests = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F:]+)', result)
                if len(digests) != 1 or (not args.rehearse and digests[0].replace(':', '').lower() != args.certificate_sha256):
                    raise ValueError('unexpected APK signing certificate')
                if not all(re.search(r'Verified using v' + v + r' scheme \([^\n]*\): true', result) for v in ('2', '3')):
                    raise ValueError('APK lacks verified v2 and v3 signatures')
                artifacts.check_metadata(args.build_tools / 'aapt2', dest, m['version_code'], m['version_name'])
                signed.append({'profile': e['profile'], 'file': e['file'], 'unsigned_sha256': e['sha256'],
                               'signed_sha256': artifacts.sha256(dest), 'certificate_sha256': digests[0].replace(':', '').lower()})
            final = {'schema': 1, 'rehearsal': args.rehearse, 'unsigned_manifest_sha256': args.manifest_sha256,
                     'commit': args.approved_commit, 'run_id': str(args.approved_run), 'version_code': m['version_code'],
                     'version_name': m['version_name'], 'certificate_sha256': signed[0]['certificate_sha256'], 'apks': signed}
            if any(x['certificate_sha256'] != final['certificate_sha256'] for x in signed):
                raise ValueError('signed APKs have different certificates')
            (args.output / 'final-manifest.json').write_text(json.dumps(final, indent=2) + '\n')
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        print('release signing failed:', exc, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
