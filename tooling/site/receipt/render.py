#!/usr/bin/env python3
"""Render experimental development receipts; this is not release approval evidence."""
import argparse
import html
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[3]
DATA = Path(__file__).with_name('pilot.json')
REPO = 'https://github.com/0cwa/collieim-android'


def validate(data):
    if data.get('version') != 1 or not re.fullmatch(r'[0-9a-f]{40}', data.get('source', '')):
        raise ValueError('Unsupported receipt version or source')
    if not re.fullmatch(r'v[0-9][0-9A-Za-z.-]*', data.get('release', '')):
        raise ValueError('Invalid release tag')
    source = data['source']
    tag_commit = subprocess.check_output(['git', 'rev-parse', '--verify', data['release'] + '^{commit}'], cwd=ROOT, text=True).strip()
    if tag_commit != source:
        raise ValueError('Release tag does not match source')
    ids = set()
    for feature in data['features']:
        if not re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*', feature['id']) or feature['id'] in ids:
            raise ValueError('Invalid or duplicate feature ID')
        ids.add(feature['id'])
        if not re.fullmatch(r'[0-9a-f]{40}', feature['change']):
            raise ValueError('Invalid change commit')
        if feature['upstream'] and urlparse(feature['upstream']).scheme != 'https':
            raise ValueError('Upstream links must use HTTPS')
        subprocess.run(['git', 'merge-base', '--is-ancestor', feature['change'], source], cwd=ROOT, check=True)
        blob = subprocess.check_output(['git', 'rev-parse', '--verify', source + ':' + feature['path']], cwd=ROOT, text=True).strip()
        if blob != feature['source_blob']:
            raise ValueError('Source-bound evidence does not match release tree')
    if data['feature_ids'] != [feature['id'] for feature in data['features']]:
        raise ValueError('Feature membership differs from the editorial manifest')
    return data


def render(data, layout):
    e = html.escape
    rows = []
    for feature in data['features']:
        source = f"{REPO}/blob/{data['source']}/{feature['path']}"
        upstream = f'<a href="{e(feature["upstream"], quote=True)}">Upstream source</a> · ' if feature['upstream'] else ''
        rows.append(f'''<article><h2>{e(feature['title'])}</h2><p>{e(feature['summary'])}</p><details><summary>Origin and source</summary><p>{e(feature['origin'])}</p><details><summary>Evidence and limits</summary><p>{e(feature['limit'])}</p><p>{upstream}<a href="{REPO}/commit/{feature['change']}">Change</a> · <a href="{e(source, quote=True)}">Source at release</a></p></details></details></article>''')
    return f'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>How this release was built</title><style>
:root {{ color-scheme: light dark; font-family: system-ui, sans-serif; background:#f2f1e8; color:#20382f; }} body {{ margin:0; }} main {{ max-width: 70rem; margin:auto; padding:clamp(1.25rem,5vw,4rem); }} a {{ color:#81442e; }} h1 {{ font-family: Georgia,serif; font-size:clamp(2.7rem,7vw,5.5rem); line-height:1; max-width:12ch; margin:.3em 0; }} p {{ line-height:1.65; max-width:68ch; }} header {{ padding-bottom:2rem; border-bottom:1px solid; }} .entries {{ {'display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,20rem),1fr));gap:2rem;' if layout == 'atlas' else 'max-width:52rem; margin-left:auto;'} }} article {{ padding:1.5rem 0; border-bottom:1px solid #94a897; }} h2 {{ font-family:Georgia,serif; font-size:1.7rem; }} details {{ padding:.6rem 0; }} summary {{ cursor:pointer; font-weight:700; }} :focus-visible {{ outline:3px solid #b35330; outline-offset:4px; }} @media(prefers-color-scheme:dark) {{ :root {{ background:#172921;color:#edf1df; }} a {{ color:#efae86; }} }} @media(forced-colors:active) {{ :focus-visible {{ outline:3px solid Highlight; }} }}
</style><main><header><p><a href="{REPO}/tree/{data['source']}">Source for {e(data['release'])}</a> · release candidate</p><h1>How this release was built</h1><p>Collie builds on Signal and Molly. Here are a few retained changes and the source behind them.</p><p>Development pilot · these examples are not release approval evidence.</p></header><section class="entries" aria-label="Selected changes">{''.join(rows)}</section><footer><p>Source links show the selected tree and change history. They do not establish runtime test coverage. This pilot does not yet include a verified removal example.</p></footer></main></html>'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--layout', choices=['atlas', 'story'], required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    data = validate(json.loads(DATA.read_text()))
    args.out.write_text(render(data, args.layout), encoding='utf-8')


if __name__ == '__main__':
    main()
