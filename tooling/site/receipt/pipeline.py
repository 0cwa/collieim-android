"""Offline build boundary for reviewed source snapshots and current presentation."""
import copy
import hashlib
import re
from pathlib import Path
from . import evidence, render


def build(out, receipt=None, published=None, routes='permanent'):
    receipt = copy.deepcopy(evidence.load_snapshot() if receipt is None else receipt)
    evidence.validate_receipt(receipt)
    source_digest = hashlib.sha256(evidence.encode(receipt)).hexdigest()
    if published is not None:
        if not isinstance(published, list) or any(not isinstance(tag, str) or not re.fullmatch(r'v[0-9][0-9A-Za-z.-]*', tag) for tag in published) or len(published) != len(set(published)):
            raise ValueError('Invalid published release index')
        receipt['published_releases'] = published
    destinations = {'app': 'patches/index.html', 'release': 'releases/index.html'} if routes == 'permanent' else {'app': 'pilot/app.html', 'release': 'pilot/release.html'}
    pages = {name: render.render(receipt, name, routes) for name in destinations}
    outputs = {}
    for layout, page in pages.items():
        target = out / destinations[layout]
        target.parent.mkdir(parents=True, exist_ok=True)
        raw = page.encode('utf-8')
        target.write_bytes(raw)
        outputs[destinations[layout]] = hashlib.sha256(raw).hexdigest()
    files = {str(p.relative_to(render.ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
             for p in sorted(Path(__file__).parent.glob('*.py')) if not p.name.startswith('test_')}
    manifest = {
        'build_version': 1, 'receipt_version': receipt['receipt_version'],
        'source_receipt_sha256': source_digest,
        'published_releases': receipt.get('published_releases', receipt['editorial'].get('release_history', [])),
        'reviewed_release': receipt['editorial']['release'],
        'newer_releases': render.newer_releases(receipt),
        'reviewed_release_published': render.reviewed_release_published(receipt),
        'presentation': files, 'assets': render.asset_hashes(), 'routes': render.ROUTES[routes],
        'outputs': outputs,
    }
    target = out / ('patches/build.json' if routes == 'permanent' else 'pilot/build.json')
    target.write_bytes(evidence.encode(manifest))
    return manifest
