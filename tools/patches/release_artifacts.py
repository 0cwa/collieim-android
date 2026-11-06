#!/usr/bin/env python3
"""Strict local checks shared by the release build and offline signer."""
import hashlib
import os
import re
import shutil
import stat
import subprocess
import zipfile
from pathlib import Path

PROFILES = {'molly-minimal', 'molly-continued', 'molly-foss-continued', 'collie', 'collie-foss'}
PACKAGE = 'im.molly.app'


def snapshot(source, destination):
    """Copy one regular file into a private directory without following a symlink."""
    fd = os.open(source, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        if not stat.S_ISREG(os.fstat(fd).st_mode):
            raise ValueError('input must be a regular file: ' + str(source))
        out = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(out, 'wb') as target, os.fdopen(fd, 'rb', closefd=False) as src:
            shutil.copyfileobj(src, target)
    finally:
        os.close(fd)


def sha256(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def apk_metadata(aapt2, apk):
    output = subprocess.run([str(aapt2), 'dump', 'badging', str(apk)], check=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True).stdout
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'", output, re.M)
    if not match:
        raise ValueError('cannot read APK package/version with aapt2')
    return match.groups()


def check_metadata(aapt2, apk, code, name):
    if apk_metadata(aapt2, apk) != (PACKAGE, str(code), name):
        raise ValueError('APK package or version differs from approved release')


def check_unsigned(apk):
    # APK v2/v3 signing blocks end immediately before the ZIP central directory.
    with zipfile.ZipFile(apk) as z:
        if any(re.fullmatch(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)', n, re.I) for n in z.namelist()):
            raise ValueError('APK contains a v1 signature')
        central = z.start_dir
    with open(apk, 'rb') as f:
        if central >= 16:
            f.seek(central - 16)
            if f.read(16) == b'APK Sig Block 42':
                raise ValueError('APK contains a signing block')


def validate_manifest(m, commit, run, cert):
    if m.get('schema') != 1 or m.get('commit') != commit or str(m.get('run_id')) != str(run):
        raise ValueError('manifest does not match approved commit and run')
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('approved commit must be a full lowercase SHA-1')
    if m.get('workflow_revision') != commit or m.get('package') != PACKAGE:
        raise ValueError('workflow revision or package does not match approved release')
    if not re.fullmatch(r'[1-9][0-9]*', str(m.get('run_attempt', ''))):
        raise ValueError('invalid workflow run attempt')
    if not re.fullmatch(r'[0-9a-f]{64}', cert) or m.get('certificate_sha256') != cert:
        raise ValueError('manifest certificate does not match approved fingerprint')
    if not isinstance(m.get('version_code'), int) or not 1 <= m['version_code'] <= 2100000000:
        raise ValueError('invalid version code')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]{0,79}', m.get('version_name', '')):
        raise ValueError('invalid version name')
    entries = m.get('apks')
    if not isinstance(entries, list) or len(entries) != 5 or {x.get('profile') for x in entries} != PROFILES:
        raise ValueError('manifest must contain exactly five profiles')
    for item in entries:
        if item.get('file') != item['profile'] + '.apk' or not re.fullmatch(r'[0-9a-f]{64}', item.get('sha256', '')):
            raise ValueError('invalid APK file or digest')
    return entries
