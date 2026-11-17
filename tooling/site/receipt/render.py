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
        upstream = f'<a href="{e(feature["upstream"], quote=True)}">Original adaptation source</a>' if feature['upstream'] else ''
        return f'''<details><summary>How it works &amp; where it came from</summary><p>{e(feature['origin'])}</p><details class="evidence"><summary>Evidence and limits</summary><p>{e(feature['limit'])}</p><div class="evidence-links">{upstream}<a href="{REPO}/commit/{feature['change']}">View the change</a><a href="{e(source, quote=True)}">Source in this version</a></div></details></details>'''
    icons = {
        'connection': '<circle cx="12" cy="12" r="2"/><path d="M7 7a7 7 0 0 0 0 10M17 7a7 7 0 0 1 0 10M4 4a11 11 0 0 0 0 16M20 4a11 11 0 0 1 0 16"/>',
        'navigation': '<path d="M4 5h16M4 12h16M4 19h16"/><circle cx="8" cy="5" r="1.5" fill="currentColor"/><circle cx="16" cy="12" r="1.5" fill="currentColor"/><circle cx="10" cy="19" r="1.5" fill="currentColor"/>',
        'personal': '<circle cx="12" cy="9" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
        'comfort': '<rect x="5" y="3" width="14" height="18" rx="3"/><path d="M9 7h6M10 17h4"/>',
    }
    def category(name, symbol):
        return f'<span class="category"><svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">{icons[symbol]}</svg>{e(name)}</span>'
    def evidence(feature):
        source = f"{REPO}/blob/{data['source']}/{feature['path']}"
        upstream = f'<a href="{e(feature["upstream"], quote=True)}">Original adaptation source</a>' if feature['upstream'] else ''
        return f'''<details><summary>Details and source<span class="sr-only"> for {e(feature['title'])}</span></summary><details class="evidence"><summary>Evidence and limits<span class="sr-only"> for {e(feature['title'])}</span></summary><p>{e(feature['limit'])}</p><div class="evidence-links">{upstream}<a href="{REPO}/commit/{feature['change']}">View the change</a><a href="{e(source, quote=True)}">Source in this version</a></div></details></details>'''
    story_meta = [('Navigation', 'navigation', 'Find Stories in Settings'), ('Contacts', 'personal', 'Contact badges')]
    rows = ''.join(f'''<article class="story">{category(label, symbol)}<h3>{e(title)}</h3><p>{e(f['summary'])}</p><p class="attribution">{e(f['origin'])}</p>{evidence(f)}</article>''' for f, (label, symbol, title) in zip(more, story_meta))
    release_meta = [('Unlocking', 'comfort', 'A clearer password prompt'), ('Navigation', 'navigation', 'Find Stories in Settings')]
    release = ''.join(f'''<article class="release-row"><div>{category(label, symbol)}</div><div><h3>{e(title)}</h3><p>{e(c['summary'])}</p><p class="attribution">{e(c['origin'])}</p><details><summary>Details and source<span class="sr-only"> for {e(title)}</span></summary><p><a href="{REPO}/compare/{data['release_base']}...{data['source']}">Compare release sources</a> · <a href="{REPO}/commit/{c['change']}">View related change</a></p></details></div></article>''' for c, (label, symbol, title) in zip(data['release_changes'], release_meta))
    if layout == 'app':
        content=f'''<div class="app-opening"><div class="opening"><p class="pin">{e(data['release'])} · Release candidate · Pilot</p><h1>What you get with Collie.</h1><p class="intro">Explore selected capabilities and adaptations in the whole app. Collie builds on Signal and Molly.</p><a href="{REPO}/tree/{data['source']}">View this version’s source</a></div><section class="feature"><div class="feature-mark" aria-hidden="true"><svg viewBox="0 0 96 96"><circle cx="48" cy="48" r="42" fill="none" stroke="currentColor" stroke-width="1.5" stroke-dasharray="3 6"/><circle cx="48" cy="48" r="4" fill="currentColor"/><path d="M34 34a20 20 0 0 0 0 28M62 34a20 20 0 0 1 0 28" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg></div><div>{category('Connection', 'connection')}<h2>When push is unavailable.</h2><p class="lede">{e(connected['summary'])}</p><p class="limitation">Based on RC2 source; notification delivery has not been measured.</p><p class="attribution">{e(connected['origin'])}</p>{evidence(connected)}</div></section></div><section class="section"><div class="section-heading"><h2>Made for everyday life.</h2><p>More ways Collie makes the app easier to use.</p></div><div class="story-grid">{rows}</div></section><section class="foundation"><h2>Built together, in the open.</h2><p>Collie stands on the work of Signal and Molly. Some capabilities are retained; others are adapted or built for Collie.</p><p><a href="https://github.com/signalapp/Signal-Android">Explore Signal</a> &nbsp;·&nbsp; <a href="https://github.com/mollyim/mollyim-android">Explore Molly</a></p></section>'''
    else:
        content=f'''<div class="opening release-opening"><p class="pin">{e(data['release_base_tag'])} → {e(data['release'])} · Release candidate · Pilot</p><h1>What changed in <span class="rc-mark">RC2<span class="ring" aria-hidden="true"></span></span>.</h1><p class="intro">Selected changes in this release candidate.</p></div><section class="release-section" aria-label="Selected changes">{release}</section><section class="accounting"><div><h2>Source changes in this release.</h2><p>Difference from {e(data["release_base_tag"])}</p></div><div><p class="numbers">Approx. production-source lines: <strong class="count">+{added} / −{removed}</strong></p><p>Tests excluded · path-based estimate</p><p><a href="{REPO}/compare/{data["release_base"]}...{data["source"]}">RC1 → RC2 source diff</a> · {data["source"][:7]}</p><details><summary>What this counts</summary><p>Counts use file paths to classify production, tests, translations and generated files. Classification is approximate; test lines are separate. This is the final source difference, not a measure of effort or quality.</p><p><a href="{REPO}/compare/{data["release_base"]}...{data["source"]}">See the source difference</a></p></details></div></section><section class="foundation"><h2>There is more to Collie.</h2><p>Explore the capabilities and choices that make up the whole app.</p><a href="app.html">Explore the app as a whole →</a></section>'''
    return f'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Collie · The story behind the app</title><style>
@font-face{{font-family:Manrope;src:url(data:font/ttf;base64,{manrope}) format("truetype");font-weight:200 800}} @font-face{{font-family:Sora;src:url(data:font/ttf;base64,{sora}) format("truetype");font-weight:100 800}}
*{{box-sizing:border-box}}:root{{color-scheme:light dark;--page:#f9fbf4;--ink:#24130d;--muted:#5a4033;--line:#a5b39b;--link:#2f4f3a;--soft:#edf2e4}}body{{margin:0;background:var(--page);color:var(--ink);font:400 1.0625rem/1.6 Manrope,sans-serif}}a{{color:var(--link);text-underline-offset:.22em}}.shell{{max-width:1160px;margin:auto;padding:0 clamp(1.25rem,4vw,2.5rem)}}.top{{background:#24130d;color:#edf2e4}}.top nav{{display:flex;align-items:center;gap:1.8rem;flex-wrap:wrap;padding:1.15rem clamp(1.25rem,4vw,2.5rem)}}.top a{{color:inherit}}.brand{{font:600 1.5rem Sora,sans-serif;margin-right:auto;text-decoration:none}}.top [aria-current]{{color:#f2982b;text-decoration-thickness:2px}}.app-opening{{display:grid;grid-template-columns:1fr 1.1fr;align-items:center;gap:clamp(2rem,5vw,4rem);padding:clamp(2rem,5vw,4.5rem) 0}}.opening{{max-width:35rem}}.release-opening{{position:relative;padding:clamp(2rem,4vw,3rem) 0 .5rem}}.rc-mark{{position:relative;display:inline-block;isolation:isolate}}.ring{{position:absolute;z-index:-1;width:1.15em;height:1.15em;right:-1.05em;top:-.2em;border:1px dashed #d85b09;border-radius:50%;opacity:.65;pointer-events:none}}h1{{font:400 clamp(2.35rem,5vw,4rem)/1.06 Manrope,sans-serif;letter-spacing:-.03em;margin:.35em 0;max-width:14ch;text-wrap:balance}}h2{{font:400 clamp(1.75rem,3.2vw,2.7rem)/1.12 Manrope,sans-serif;letter-spacing:-.025em;margin:0 0 .5em;text-wrap:balance}}h3{{font:500 1.35rem/1.3 Sora,sans-serif;margin:.55em 0}}p{{max-width:65ch}}.intro{{font-size:1.12rem;max-width:40ch}}.pin{{font-size:.9rem;color:var(--muted);font-variant-numeric:tabular-nums}}.feature{{position:relative;background:#2f4f3a;color:#edf2e4;padding:clamp(1.4rem,3vw,2.5rem);overflow:hidden}}.feature a{{color:#edf2e4}}.feature h2{{max-width:15ch}}.feature-mark{{position:absolute;right:-1rem;top:-1rem;width:7rem;color:#f2982b;opacity:.85;pointer-events:none}}.feature-mark svg{{display:block;width:100%}}.feature .category{{padding-right:5rem}}.lede{{font-size:1.125rem;max-width:38ch}}.section,.release-section{{padding:clamp(2rem,4vw,3.5rem) 0}}.section-heading{{max-width:40rem;margin-bottom:1.5rem}}.story-grid{{display:grid;grid-template-columns:1fr 1fr;gap:2.5rem}}.story{{border-top:1px solid var(--line);padding-top:1.2rem}}.category{{display:inline-flex;gap:.55rem;align-items:center;font:600 .9rem/1.3 Sora,sans-serif;color:#6f270f}}.category svg{{width:1.45rem;height:1.45rem;flex:none}}.feature .category{{color:#f9c783}}.attribution{{font-size:.9rem;color:var(--muted)}}.feature .attribution,.feature .limitation{{color:#dfe9cc}}.limitation{{font-size:.9rem}}details{{margin-top:1rem}}summary{{cursor:pointer;font-weight:700;padding:.5rem 0;min-height:44px}}.evidence{{margin-top:.75rem;padding-left:1rem;border-left:1px solid currentColor}}.evidence-links{{display:flex;flex-direction:column;align-items:flex-start;gap:.65rem;margin:1rem 0}}.accounting{{border-top:1px solid var(--line);padding:1.5rem 0;display:grid;grid-template-columns:1fr 1fr;gap:2rem}}.accounting h2{{font-size:1.65rem}}.numbers{{font-size:1.3rem;font-variant-numeric:tabular-nums}}.count{{white-space:nowrap}}.foundation{{border-top:1px solid var(--line);padding:2.5rem 0;margin-bottom:2rem}}.foundation h2{{font-size:1.65rem}}.release-row{{display:grid;grid-template-columns:12rem 1fr;gap:2rem;border-top:1px solid var(--line);padding:1.75rem 0}}.release-row h3{{margin-top:0}}footer{{border-top:1px solid var(--line);padding:1.5rem 0;color:var(--muted);font-size:.9rem}}.sr-only{{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}}:focus-visible{{outline:3px solid #f2982b;outline-offset:4px}}::selection{{background:#2f4f3a;color:#edf2e4}}@media(max-width:650px){{.app-opening,.story-grid,.release-row,.accounting{{grid-template-columns:1fr;gap:1rem}}.app-opening{{padding:2rem 0;gap:1.5rem}}.opening h1{{font-size:2.4rem}}.ring{{display:none}}.release-opening{{padding:2rem 0 .5rem}}.release-row h3{{margin-top:.4em}}.evidence{{padding-left:0;border-left:0}}.top nav{{gap:1rem}}.brand{{width:100%}}.feature-mark{{width:5.5rem}}}}@media(prefers-color-scheme:dark){{:root{{--page:#24130d;--ink:#edf2e4;--muted:#c9d5b3;--line:#687e59;--link:#dfe9cc;--soft:#3c2115}}.category{{color:#f3b884}}.feature .category{{color:#f9c783}}}}@media(forced-colors:active){{.feature{{border:1px solid CanvasText}}}}
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
