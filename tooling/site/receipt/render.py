#!/usr/bin/env python3
"""Render experimental development receipts; this is not release approval evidence."""
import argparse
import base64
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
        for path in feature.get('absent_paths', []):
            if subprocess.run(['git', 'cat-file', '-e', source + ':' + path], cwd=ROOT, stderr=subprocess.DEVNULL).returncode == 0:
                raise ValueError('Expected removed path is present in release tree')
    if data['feature_ids'] != [feature['id'] for feature in data['features']]:
        raise ValueError('Feature membership differs from the editorial manifest')
    return data


def render(data, layout):
    e = html.escape
    fonts = ROOT / 'docs/site/assets/fonts'
    manrope = base64.b64encode((fonts / 'manrope.ttf').read_bytes()).decode('ascii')
    sora = base64.b64encode((fonts / 'sora.ttf').read_bytes()).decode('ascii')
    rows = []
    links = []
    for feature in data['features']:
        title = e(feature['title'])
        anchor = e(feature['id'], quote=True)
        source = f"{REPO}/blob/{data['source']}/{feature['path']}"
        upstream = f'<a href="{e(feature["upstream"], quote=True)}">Upstream source for {title}</a> · ' if feature['upstream'] else ''
        links.append(f'<li><a href="#{anchor}">{title}</a></li>')
        rows.append(f'''<article id="{anchor}"><h2>{title}</h2><p>{e(feature['summary'])}</p><details><summary>Origin and adaptation</summary><p>{e(feature['origin'])}</p><details><summary>Evidence and limits</summary><p>{e(feature['limit'])}</p><p>{upstream}<a href="{REPO}/commit/{feature['change']}">Change for {title}</a> · <a href="{e(source, quote=True)}">{title} source at release</a></p></details></details></article>''')
    navigation = f'<nav aria-label="Changes in this receipt"><h2>In this release</h2><ul>{"".join(links)}</ul></nav>' if layout == 'story' else ''
    return f'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>How this release was built</title><style>
@font-face{{font-family:Manrope;src:url(data:font/ttf;base64,{manrope}) format("truetype");font-weight:200 800}} @font-face{{font-family:Sora;src:url(data:font/ttf;base64,{sora}) format("truetype");font-weight:100 800}}
*{{box-sizing:border-box}} :root{{color-scheme:light dark;--page:#f9fbf4;--ink:#24130d;--link:#2f4f3a;--rule:#9da991;--surface:#edf2e4}} body{{margin:0;background:var(--page);color:var(--ink);font:400 1.05rem/1.65 Manrope,sans-serif}} main{{max-width:74rem;margin:auto;padding:clamp(1.25rem,5vw,4rem)}} a{{color:var(--link);text-underline-offset:.2em}} h1{{font:400 clamp(2.6rem,6vw,5rem)/1.08 Manrope,sans-serif;max-width:13ch;letter-spacing:-.03em;margin:.4em 0}} h2{{font:400 1.45rem/1.3 Sora,sans-serif}} p{{max-width:68ch}} header{{padding:2rem 0 3rem;border-bottom:1px solid var(--rule)}} header p{{max-width:53ch}} .body{{{'display:grid;grid-template-columns:minmax(12rem, 1fr) minmax(0, 3fr);gap:clamp(2rem,6vw,6rem);' if layout == 'story' else ''}}} nav{{padding-top:2rem}} nav h2{{font-size:1rem}} nav ul{{padding-left:1.2rem}} nav li{{margin:.8rem 0}} .entries{{{'display:grid;grid-template-columns:repeat(2,minmax(0,1fr));column-gap:3rem;' if layout == 'atlas' else ''}}} article{{padding:1.5rem 0 2rem;border-bottom:1px solid var(--rule);scroll-margin-top:1rem}} details{{padding:.6rem 0}} summary{{cursor:pointer;font-weight:700;min-height:44px}} details details{{padding-left:1rem}} :focus-visible{{outline:3px solid #d85b09;outline-offset:4px}} footer{{padding:2rem 0}} ::selection{{background:#2f4f3a;color:#edf2e4}} @media(max-width:650px){{.body,.entries{{display:block}} nav{{padding-top:1rem}} article{{padding:1.25rem 0}}}} @media(prefers-color-scheme:dark){{:root{{--page:#24130d;--ink:#edf2e4;--link:#dfe9cc;--rule:#687e59;--surface:#2b1c14}}}} @media(forced-colors:active){{:focus-visible{{outline:3px solid Highlight}}}}
</style><main><header><p><a href="{REPO}/tree/{data['source']}">Source for {e(data['release'])}</a> · release candidate</p><h1>How this release was built</h1><p>Collie builds on Signal and Molly. Here are a few retained changes and the source behind them.</p><p>Development pilot · these examples are not release approval evidence.</p></header><div class="body">{navigation}<section class="entries" aria-label="Selected changes">{''.join(rows)}</section></div><footer><p>Source links show the selected tree and change history. They do not establish runtime test coverage.</p></footer></main></html>'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--layout', choices=['atlas', 'story'], required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    data = validate(json.loads(DATA.read_text()))
    args.out.write_text(render(data, args.layout), encoding='utf-8')


if __name__ == '__main__':
    main()
