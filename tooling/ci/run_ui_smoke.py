#!/usr/bin/env python3
"""Run the curated per-class API 35 UI instrumentation smoke suite."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / 'tooling/ci/ui_smoke_manifest.json'
ANIMATION_SETTINGS = ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale')


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def run(argv, *, serial=None, timeout=120, check=True, capture=True):
    command = list(argv)
    if serial and command[0] == 'adb':
        command[1:1] = ['-s', serial]
    return subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE if capture else None,
                          stderr=subprocess.STDOUT if capture else None, timeout=timeout, check=check)


def parse_instrumentation(raw, classname):
    status = {}
    events = []
    run_codes = []
    for line in raw.splitlines():
        kind, sep, value = line.partition(': ')
        if not sep:
            continue
        if kind == 'INSTRUMENTATION_STATUS':
            key, _, item = value.partition('=')
            status[key] = item
        elif kind == 'INSTRUMENTATION_STATUS_CODE':
            events.append({'code': value, 'class': status.get('class'), 'test': status.get('test')})
            status.clear()
        elif kind == 'INSTRUMENTATION_CODE':
            run_codes.append(value)
        elif kind == 'INSTRUMENTATION_FAILED' or line.startswith('INSTRUMENTATION_RESULT: shortMsg='):
            raise ValueError(line)
    starts = [e for e in events if e['code'] == '1']
    ended = [e for e in events if e['code'] in ('0', '-2', '-3', '-4')]
    failures = [e for e in ended if e['code'] == '-2']
    skips = [e for e in ended if e['code'] in ('-3', '-4')]
    passed = [e for e in ended if e['code'] == '0']
    if any(e['class'] != classname for e in starts + ended):
        raise ValueError('instrumentation reported an unexpected class')
    if not starts or len(starts) != len(ended) or failures or skips or len(passed) != len(starts) or run_codes != ['-1']:
        raise ValueError(f'instrumentation not clean: started={len(starts)} passed={len(passed)} failures={len(failures)} skips={len(skips)} run_codes={run_codes}')
    return [event['test'] for event in passed]


def manifest_xml(apk, output):
    analyzer = os.environ.get('APKANALYZER')
    if not analyzer:
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
        if sdk:
            analyzer = str(Path(sdk) / 'cmdline-tools/latest/bin/apkanalyzer')
    if not analyzer or not Path(analyzer).is_file():
        raise ValueError('apkanalyzer is required to record APK manifest metadata')
    result = subprocess.run([analyzer, 'manifest', 'print', str(apk)], cwd=ROOT, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
    output.write_text(result.stdout)


def execute(args):
    manifest = json.loads(MANIFEST.read_text())
    edition = manifest['editions'][args.edition]
    expected_api = args.expected_api_level or manifest['api_level']
    classes = args.classes or edition['classes']
    if len(classes) != len(set(classes)) or any(c not in edition['classes'] for c in classes):
        raise ValueError('--classes must be a unique subset of the edition manifest')
    output = Path(args.output_dir).resolve()
    output.mkdir(parents=True, exist_ok=True)
    app_apk = Path(args.app_apk or ROOT / edition['app_apk']).resolve()
    test_apk = Path(args.test_apk or ROOT / edition['test_apk']).resolve()
    for apk in (app_apk, test_apk):
        if not apk.is_file():
            raise ValueError(f'missing APK: {apk}')
    apk_names = (edition['app_apk'], edition['test_apk'])
    hashes = {apk_names[0]: digest(app_apk), apk_names[1]: digest(test_apk)}
    (output / 'apk-before.txt').write_text(''.join(f'{sha}  {path}\n' for path, sha in hashes.items()))
    manifest_xml(app_apk, output / 'app-manifest.xml')
    manifest_xml(test_apk, output / 'test-manifest.xml')
    source = run(['git', 'rev-parse', 'HEAD']).stdout.strip()
    tree = run(['git', 'rev-parse', 'HEAD^{tree}']).stdout.strip()
    (output / 'source.txt').write_text(f'{source}\n{tree}\n')
    api = run(['adb', 'shell', 'getprop', 'ro.build.version.sdk'], serial=args.serial).stdout.strip()
    fingerprint = run(['adb', 'shell', 'getprop', 'ro.build.fingerprint'], serial=args.serial).stdout.strip()
    (output / 'device-api.txt').write_text(api + '\n')
    (output / 'device-fingerprint.txt').write_text(fingerprint + '\n')
    if api != str(expected_api) or not fingerprint:
        raise ValueError(f'expected API {expected_api}; device reported API {api}')
    animation_scales = {
        setting: run(['adb', 'shell', 'settings', 'get', 'global', setting], serial=args.serial).stdout.strip()
        for setting in ANIMATION_SETTINGS
    }
    (output / 'animations.json').write_text(json.dumps({
        'original': animation_scales,
        'applied_during_tests': {setting: '0' for setting in ANIMATION_SETTINGS},
    }, indent=2) + '\n')
    if not args.skip_install:
        run(['adb', 'install', '-r', '-t', str(app_apk)], serial=args.serial, timeout=180)
        run(['adb', 'install', '-r', '-t', str(test_apk)], serial=args.serial, timeout=180)
    run(['adb', 'shell', 'svc', 'power', 'stayon', 'true'], serial=args.serial)
    run(['adb', 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'], serial=args.serial)
    run(['adb', 'shell', 'wm', 'dismiss-keyguard'], serial=args.serial)
    summary = []
    run_failures = []
    animation_restore_failures = []
    (output / 'classes.json').write_text(json.dumps(classes, indent=2) + '\n')
    for classname in classes:
        started = time.monotonic()
        log = output / (classname.replace('.', '_') + '.log')
        raw = ''
        try:
            for setting in ANIMATION_SETTINGS:
                run(['adb', 'shell', 'settings', 'put', 'global', setting, '0'], serial=args.serial)
            # Every test class, including the first, starts with a fresh application sandbox.
            clear = run(['adb', 'shell', 'pm', 'clear', edition['application_id']],
                        serial=args.serial, timeout=60, check=False)
            if clear.returncode != 0:
                raw = clear.stdout or ''
                raise ValueError(f'pm clear exited {clear.returncode}: {clear.stdout}')
            run(['adb', 'logcat', '-c'], serial=args.serial)
            instrumentation_args = edition.get('instrumentation_args', {}).get(classname, {})
            command = ['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', classname]
            for key, value in sorted(instrumentation_args.items()):
                command.extend(['-e', key, value])
            command.append(f'{edition["test_application_id"]}/{edition["runner"]}')
            result = run(command, serial=args.serial, timeout=600, check=False)
            raw = result.stdout or ''
            log.write_text(raw)
            passing_tests = parse_instrumentation(result.stdout or '', classname)
            expected_tests = edition['methods'][classname]
            if sorted(passing_tests) != sorted(expected_tests):
                raise ValueError(f'expected test methods {expected_tests}; observed {passing_tests}')
            if result.returncode != 0:
                raise ValueError(f'adb exited {result.returncode}')
            elapsed = round(time.monotonic() - started, 3)
            summary.append({'class': classname, 'passed': len(passing_tests), 'tests': passing_tests, 'seconds': elapsed})
            print(f'PASS {classname}: {len(passing_tests)} tests in {elapsed:.3f}s')
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            error_output = getattr(error, 'stdout', None)
            if error_output is not None:
                raw = error_output.decode(errors='replace') if isinstance(error_output, bytes) else error_output
            log.write_text(raw)
            elapsed = round(time.monotonic() - started, 3)
            run_failures.append(f'{classname}: {error}')
            summary.append({'class': classname, 'passed': 0, 'seconds': elapsed, 'error': str(error)})
            try:
                diagnostics = run(['adb', 'logcat', '-b', 'all', '-d', '-t', '8000', '-v', 'threadtime'],
                                  serial=args.serial, check=False, timeout=60)
                (output / (classname.replace('.', '_') + '.logcat.txt')).write_text(diagnostics.stdout or '')
            except (OSError, subprocess.SubprocessError):
                pass
            print(f'FAIL {classname}: {error}', file=sys.stderr)
        finally:
            for setting, value in animation_scales.items():
                command = (['adb', 'shell', 'settings', 'delete', 'global', setting]
                           if value in ('', 'null')
                           else ['adb', 'shell', 'settings', 'put', 'global', setting, value])
                try:
                    restored = run(command, serial=args.serial, check=False)
                    if restored.returncode != 0:
                        animation_restore_failures.append(f'{setting}: adb exited {restored.returncode}')
                except (OSError, subprocess.SubprocessError) as error:
                    animation_restore_failures.append(f'{setting}: {error}')
    if animation_restore_failures:
        run_failures.append('could not restore device animation settings: ' + '; '.join(animation_restore_failures))
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    after = {apk_names[0]: digest(app_apk), apk_names[1]: digest(test_apk)}
    (output / 'apk-after.txt').write_text(''.join(f'{sha}  {path}\n' for path, sha in after.items()))
    (output / 'exit-code.txt').write_text(('1' if run_failures else '0') + '\n')
    if run_failures:
        raise ValueError('; '.join(run_failures))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--edition', required=True, choices=('stable', 'beta'))
    parser.add_argument('--app-apk')
    parser.add_argument('--test-apk')
    parser.add_argument('--serial')
    parser.add_argument('--output-dir', default='build/ui-smoke')
    parser.add_argument('--classes', nargs='+')
    parser.add_argument('--expected-api-level', type=int)
    parser.add_argument('--skip-install', action='store_true')
    args = parser.parse_args(argv)
    try:
        return execute(args)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError, json.JSONDecodeError) as error:
        print(f'UI smoke failed: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
