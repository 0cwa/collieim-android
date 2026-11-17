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
    for feature in data['features']:
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
    if subprocess.check_output(['git', 'rev-parse', '--verify', data['release_base_tag'] + '^{commit}'], cwd=ROOT, text=True).strip() != data['release_base']:
        raise ValueError('Release baseline does not match tag')
    for change in data['release_changes']:
        changed = subprocess.check_output(['git', 'diff', '--name-only', data['release_base'], source, '--', change['path']], cwd=ROOT, text=True).strip()
        if not changed:
            raise ValueError('Release entry has no change at its cited path')
    return data


def render(data, layout):
    e = html.escape
    fonts = ROOT / 'docs/site/assets/fonts'
    manrope = base64.b64encode((fonts / 'manrope.ttf').read_bytes()).decode('ascii')
    sora = base64.b64encode((fonts / 'sora.ttf').read_bytes()).decode('ascii')
    import metrics
    counts = metrics.release_metrics(data['release_base'], data['source'])
    added, removed = counts['production']
    connected = data['features'][1]
    more = [data['features'][3], data['features'][0]]
    def evidence(feature):
        source = f"{REPO}/blob/{data['source']}/{feature['path']}"
        upstream = f'<a href="{e(feature["upstream"], quote=True)}">Original adaptation source</a> · ' if feature['upstream'] else ''
        return f'''<details><summary>How it works &amp; where it came from</summary><p>{e(feature['origin'])}</p><details><summary>Evidence and limits</summary><p>{e(feature['limit'])}</p><p>{upstream}<a href="{REPO}/commit/{feature['change']}">View the change</a> · <a href="{e(source, quote=True)}">Source in this version</a></p></details></details>'''
    rows=''.join(f'''<article class="story"><h3>{e(f['title'])}</h3><p>{e(f['summary'])}</p>{evidence(f)}</article>''' for f in more)
    release=''.join(f'''<article class="release-row"><div><span class="marker" aria-hidden="true"></span></div><div><h3>{e(c['title'])}</h3><p>{e(c['summary'])}</p><details><summary>Origin and source</summary><p>{e(c['origin'])}</p><p><a href="{REPO}/compare/{data['release_base']}...{data['source']}">Compare release sources</a> · <a href="{REPO}/commit/{c['change']}">View related change</a></p></details></div></article>''' for c in data['release_changes'])
    if layout == 'app':
        content=f'''<div class="opening"><h1>What makes Collie different.</h1><p class="intro">Explore selected Collie capabilities and adaptations in the whole app. Collie builds on Signal and Molly.</p><p class="pin">Based on <a href="{REPO}/tree/{data['source']}">{e(data['release'])}</a> · release candidate</p></div><section class="feature"><div class="feature-label"><svg class="wave" aria-hidden="true" viewBox="0 0 96 64"><circle cx="48" cy="32" r="5" fill="currentColor"/><path d="M34 18a20 20 0 0 0 0 28M62 18a20 20 0 0 1 0 28M23 7a35 35 0 0 0 0 50M73 7a35 35 0 0 1 0 50" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round"/></svg><p>STAY CONNECTED</p></div><div><h2>Stay connected when push is unavailable.</h2><p class="lede">{e(connected['summary'])}</p>{evidence(connected)}</div></section><section class="section"><div class="section-heading"><h2>Made for everyday life.</h2><p>More ways Collie makes the app easier to use.</p></div><div class="story-grid">{rows}</div></section><section class="foundation"><h2>Built together, in the open.</h2><p>Collie stands on the work of Signal and Molly. Some capabilities are retained; others are adapted or built for Collie.</p><p><a href="https://github.com/signalapp/Signal-Android">Explore Signal</a> &nbsp;·&nbsp; <a href="https://github.com/mollyim/mollyim-android">Explore Molly</a></p></section>'''
    else:
        content=f'''<div class="opening release-opening"><p class="pin">Release candidate · {e(data['release'])}</p><h1>What changed in this release.</h1><p class="intro">Changes in {e(data['release'])} compared with {e(data['release_base_tag'])}.</p><p class="pin"><a href="{REPO}/compare/{data['release_base']}...{data['source']}">Explore the source difference</a></p></div><section class="release-section"><div class="section-heading"><h2>For everyday use.</h2><p>Selected changes in this candidate.</p></div>{release}</section><section class="accounting"><h2>The work behind this release.</h2><p>Difference from {e(data["release_base_tag"])}</p><p class="numbers">Production lines: <strong>+{added} / −{removed}</strong></p><details><summary>What this counts</summary><p>Counts use file paths to classify production, tests, translations and generated files. Classification is approximate; test lines are separate. This is the final source difference, not a measure of effort or quality.</p><p><a href="{REPO}/compare/{data["release_base"]}...{data["source"]}">See the source difference</a></p></details></section><section class="foundation"><h2>There is more to Collie.</h2><p>Explore the capabilities and choices that make up the whole app.</p><a href="app.html">Explore the app as a whole →</a></section>'''
    return f'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Collie · The story behind the app</title><style>
@font-face{{font-family:Manrope;src:url(data:font/ttf;base64,{manrope}) format("truetype");font-weight:200 800}} @font-face{{font-family:Sora;src:url(data:font/ttf;base64,{sora}) format("truetype");font-weight:100 800}}
*{{box-sizing:border-box}}:root{{color-scheme:light dark;--page:#f9fbf4;--ink:#24130d;--muted:#5a4033;--line:#a5b39b;--link:#2f4f3a;--soft:#edf2e4}}body{{margin:0;background:var(--page);color:var(--ink);font:400 1.05rem/1.65 Manrope,sans-serif}}a{{color:var(--link);text-underline-offset:.22em}}.shell{{max-width:1160px;margin:auto;padding:0 clamp(1.25rem,5vw,3rem)}}.top{{background:#24130d;color:#edf2e4}}.top nav{{display:flex;align-items:center;gap:1.8rem;flex-wrap:wrap;padding-top:1.25rem;padding-bottom:1.25rem}}.top a{{color:inherit}}.brand{{font:600 1.5rem Sora,sans-serif;margin-right:auto;text-decoration:none}}.top [aria-current]{{color:#f2982b;text-decoration-thickness:2px}}.opening{{padding:clamp(3rem,7vw,6rem) 0;max-width:63rem}}.label,.kind,.feature-label p{{font:700 .78rem Sora,sans-serif;letter-spacing:.09em}}h1{{font:400 clamp(3.1rem,7.7vw,6rem)/1.03 Manrope,sans-serif;letter-spacing:-.035em;max-width:13ch;margin:.3em 0}}h2{{font:400 clamp(2rem,4.3vw,3.5rem)/1.12 Manrope,sans-serif;letter-spacing:-.025em;margin:0 0 .5em}}h3{{font:500 1.45rem/1.3 Sora,sans-serif;margin:.4em 0}}p{{max-width:65ch}}.intro{{font-size:clamp(1.15rem,2vw,1.4rem);max-width:42ch}}.pin{{font-size:.9rem;margin-top:2rem}}.feature{{background:#2f4f3a;color:#edf2e4;display:grid;grid-template-columns:1fr 2fr;gap:2rem;padding:clamp(1.6rem,5vw,4rem)}}.feature a{{color:#edf2e4}}.feature h2{{max-width:13ch}}.wave{{display:block;color:#f2982b;width:5rem;height:auto}}.lede{{font-size:1.25rem;max-width:38ch}}.section,.release-section{{padding:clamp(3rem,7vw,6rem) 0}}.section-heading{{max-width:40rem;margin-bottom:2rem}}.story-grid{{display:grid;grid-template-columns:1fr 1fr;gap:3rem}}.story{{border-top:1px solid var(--line);padding-top:1.2rem}}.kind,.category{{color:#6f270f}}details{{margin-top:1.4rem}}summary{{cursor:pointer;font-weight:700;padding:.5rem 0;min-height:44px}}.accounting{{border-top:1px solid var(--line);padding:3rem 0}}.numbers{{font-size:1.3rem;font-variant-numeric:tabular-nums}}.foundation{{background:var(--soft);padding:clamp(1.7rem,5vw,4rem);margin-bottom:4rem}}.foundation h2{{max-width:18ch}}.release-row{{display:grid;grid-template-columns:1fr 2fr;gap:2rem;border-top:1px solid var(--line);padding:2rem 0}}.marker{{display:inline-block;width:12px;height:12px;border:3px solid #d85b09;border-radius:50%;margin-right:.6rem}}.category{{font-weight:700;font-size:.9rem}}footer{{border-top:1px solid var(--line);padding:2rem 0;color:var(--muted);font-size:.9rem}}:focus-visible{{outline:3px solid #f2982b;outline-offset:4px}}::selection{{background:#2f4f3a;color:#edf2e4}}@media(max-width:650px){{.feature,.story-grid,.release-row{{grid-template-columns:1fr;gap:1rem}}.top nav{{gap:1rem}}.brand{{width:100%}}}}@media(prefers-color-scheme:dark){{:root{{--page:#24130d;--ink:#edf2e4;--muted:#c9d5b3;--line:#687e59;--link:#dfe9cc;--soft:#3c2115}}.kind,.category{{color:#f3b884}}}}@media(forced-colors:active){{.feature{{border:1px solid CanvasText}}}}
</style><div class="top"><nav class="shell" aria-label="Explore Collie"><a class="brand" href="app.html">collie</a><a href="app.html" {'aria-current="page"' if layout=='app' else ''}>The app</a><a href="release.html" {'aria-current="page"' if layout=='release' else ''}>This release</a></nav></div><main class="shell">{content}<footer><p>Enjoying Collie? <a href="https://collie.im/#donate-title">Support its development</a>.</p><p>This is an experimental preview. Source links show selected evidence; they are not a guarantee of runtime behavior or release approval.</p></footer></main></html>'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--layout', choices=['app', 'release'], required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    data = validate(json.loads(DATA.read_text()))
    args.out.write_text(render(data, args.layout), encoding='utf-8')


if __name__ == '__main__':
    main()
