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
    def require_text(record, fields):
        if not all(isinstance(record.get(key), str) and record[key].strip() for key in fields):
            raise ValueError('Missing required editorial text: ' + ', '.join(fields))

    if data.get('version') != 2 or not re.fullmatch(r'[0-9a-f]{40}', data.get('source', '')):
        raise ValueError('Unsupported receipt version or source')
    require_text(data, ('app_headline', 'release_headline'))
    if not re.fullmatch(r'v[0-9][0-9A-Za-z.-]*', data.get('release', '')):
        raise ValueError('Invalid release tag')
    if not re.fullmatch(r'[0-9a-f]{40}', data.get('release_base', '')) or not re.fullmatch(r'v[0-9][0-9A-Za-z.-]*', data.get('release_base_tag', '')):
        raise ValueError('Invalid release baseline')
    if data.get('release_status') not in ('Release candidate', 'Beta', 'Stable', 'In development'):
        raise ValueError('Unknown release status')
    if data.get('membership', {}).get('status') != 'unknown':
        raise ValueError('Approved membership adapter is not available')
    if sum(f.get('placement') == 'lead' for f in data['features']) > 1:
        raise ValueError('At most one lead subject is allowed')
    app_visuals = {'connection': 'Connection', 'navigation': 'Navigation', 'personal': 'Contacts', 'shield': 'Choices'}
    for f in data['features']:
        require_text(f, ('title', 'display_title', 'summary', 'origin', 'category', 'path', 'limit'))
        if f.get('placement') not in ('lead', 'standard', 'hidden') or app_visuals.get(f.get('visual')) != f.get('category'):
            raise ValueError('Unknown subject placement or visual category')
        if 'diagram' in f:
            d = f['diagram']
            if not isinstance(d, dict) or not isinstance(d.get('caption'), str) or not isinstance(d.get('cases'), list) or not d['cases'] or any(not isinstance(c, dict) or not all(isinstance(c.get(k), str) and c[k] for k in ('condition', 'result')) for c in d['cases']):
                raise ValueError('Incomplete subject diagram')
    release_visuals = {'clearance': 'Unlocking', 'navigation': 'Navigation'}
    for c in data['release_changes']:
        require_text(c, ('title', 'display_title', 'summary', 'origin', 'category', 'change_type', 'path'))
        if release_visuals.get(c.get('visual')) != c.get('category'):
            raise ValueError('Unknown release illustration category')
        keys = ('top', 'gap', 'bottom', 'caption') if c['visual'] == 'clearance' else ('from', 'to', 'note', 'caption')
        if not isinstance(c.get('diagram'), dict) or not all(isinstance(c['diagram'].get(k), str) and c['diagram'][k] for k in keys):
            raise ValueError('Incomplete release illustration')
    source = data['source']
    tag_commit = subprocess.check_output(['git', 'rev-parse', '--verify', 'refs/tags/' + data['release'] + '^{commit}'], cwd=ROOT, text=True).strip()
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
        for support in feature.get('supporting_paths', []):
            blob = subprocess.check_output(['git', 'rev-parse', '--verify', source + ':' + support['path']], cwd=ROOT, text=True).strip()
            if blob != support['blob']:
                raise ValueError('Supporting evidence does not match release tree')
        for path in feature.get('absent_paths', []):
            if subprocess.run(['git', 'cat-file', '-e', source + ':' + path], cwd=ROOT, stderr=subprocess.DEVNULL).returncode == 0:
                raise ValueError('Expected removed path is present in release tree')
    if subprocess.check_output(['git', 'rev-parse', '--verify', 'refs/tags/' + data['release_base_tag'] + '^{commit}'], cwd=ROOT, text=True).strip() != data['release_base']:
        raise ValueError('Release baseline does not match tag')
    release_commits = set(subprocess.check_output(['git', 'rev-list', data['release_base'] + '..' + source], cwd=ROOT, text=True).splitlines())
    for change in data['release_changes']:
        if not re.fullmatch(r'[0-9a-f]{40}', change.get('change', '')):
            raise ValueError('Invalid release change commit')
        if change['change'] not in release_commits:
            raise ValueError('Release change is outside the comparison range')
        parents = subprocess.check_output(['git', 'rev-list', '--parents', '-n', '1', change['change']], cwd=ROOT, text=True).split()
        if len(parents) != 2:
            raise ValueError('Release change evidence must be a single-parent commit')
        expected = change['path'].encode('utf-8')
        touched = subprocess.check_output(['git', '--literal-pathspecs', 'diff-tree', '--no-commit-id', '--name-only', '-z', '-r', '--no-renames', change['change'], '--', change['path']], cwd=ROOT).split(b'\0')
        if expected not in touched:
            raise ValueError('Release change did not touch its cited path')
        changed = subprocess.check_output(['git', '--literal-pathspecs', 'diff', '--name-only', '-z', '--no-renames', data['release_base'], source, '--', change['path']], cwd=ROOT).split(b'\0')
        if expected not in changed:
            raise ValueError('Release entry has no change at its cited path')
    return data


def asset_hashes():
    import hashlib
    paths = ('docs/site/assets/fonts/manrope.ttf', 'docs/site/assets/fonts/sora.ttf', 'docs/site/assets/art/landscape-hero.svg', 'docs/site/assets/collie-icon.svg')
    return {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in paths}


def render(receipt, layout):
    """Render a frozen development receipt without querying Git or the network."""
    from urllib.parse import quote
    if receipt.get('receipt_version') != 1:
        raise ValueError('Unsupported frozen receipt')
    if receipt.get('asset_hashes') != asset_hashes():
        raise ValueError('Receipt assets differ from this renderer checkout')
    data = receipt['editorial']
    e = html.escape
    fonts = ROOT / 'docs/site/assets/fonts'
    manrope = base64.b64encode((fonts / 'manrope.ttf').read_bytes()).decode('ascii')
    sora = base64.b64encode((fonts / 'sora.ttf').read_bytes()).decode('ascii')
    landscape = (ROOT / 'docs/site/assets/art/landscape-hero.svg').read_text(encoding='utf-8')
    artwork = base64.b64encode(landscape.replace('viewBox="0 0 1024 612"', 'viewBox="0 240 1024 372"').encode('utf-8')).decode('ascii')
    mark = base64.b64encode((ROOT / 'docs/site/assets/collie-icon.svg').read_bytes()).decode('ascii')
    added, removed = receipt['release_metrics']['production']
    def evidence(item, release=False):
        source = f"{REPO}/blob/{data['source']}/{quote(item['path'], safe='/')}"
        upstream = f'<a href="{e(item["upstream"], quote=True)}">Original source</a>' if item.get('upstream') else ''
        limit = f'<p>{e(item["limit"])}</p>' if item.get('limit') else ''
        supporting = ''.join(f'<a href="{REPO}/blob/{data["source"]}/{quote(ref["path"], safe="/")}">{e(ref["label"])}</a>' for ref in item.get('supporting_paths', []))
        return f'''<details><summary>Inspect change and source<span class="sr-only">: {e(item['display_title'])}</span></summary><div class="detail-body">{limit}<div class="evidence-links">{upstream}{supporting}<a href="{REPO}/commit/{e(item['change'], quote=True)}">View the change</a><a href="{e(source, quote=True)}">Source in this version</a></div></div></details>'''
    def diagram(item):
        d = item['diagram']
        if item['visual'] == 'clearance':
            return f'<figure class="diagram clearance"><div class="action-bar">{e(d["top"])}</div><div class="clearance-space"><span>{e(d["gap"])}</span></div><div class="password-form">{e(d["bottom"])}</div><figcaption>{e(d["caption"])}</figcaption></figure>'
        if item['visual'] == 'navigation':
            return f'<figure class="diagram navigation"><div class="nav-path"><span>{e(d["from"])}</span><span aria-hidden="true">→</span><strong>{e(d["to"])}</strong></div><p>{e(d["note"])}</p><figcaption>{e(d["caption"])}</figcaption></figure>'
        raise ValueError('Unknown illustration')
    def release_story(item):
        return f'''<article class="release-story"><div class="story-copy"><p class="category">{e(item['category'])} · {e(item['change_type'])}</p><h2>{e(item['display_title'])}</h2><p>{e(item['summary'])}</p><p class="origin">{e(item['origin'])}</p></div><div class="visual">{diagram(item)}</div><div class="story-details">{evidence(item, True)}</div></article>'''
    symbols = {
        'connection': '<path d="M4 15V10a8 8 0 0 1 16 0v5l1.5 2h-19z"/><path d="M10 20h4"/>',
        'navigation': '<path d="M4 6h16M4 12h16M4 18h16"/><circle cx="8" cy="6" r="1"/><circle cx="16" cy="12" r="1"/>',
        'personal': '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
        'shield': '<path d="M12 3l8 3v5c0 5-3 8-8 10-5-2-8-5-8-10V6z"/>'}
    def subject_category(item):
        return f'<p class="category"><svg class="category-icon" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round">{symbols[item["visual"]]}</svg>{e(item["category"])}</p>'
    def subject_story(item):
        return f'''<article class="subject"><div>{subject_category(item)}<h3>{e(item['display_title'])}</h3><p>{e(item['summary'])}</p><p class="origin">{e(item['origin'])}</p>{evidence(item)}</div></article>'''
    leads = [f for f in data['features'] if f['placement'] == 'lead']
    standards = [f for f in data['features'] if f['placement'] == 'standard']
    lead = ''
    if leads:
        f = leads[0]
        d = f.get('diagram')
        graphic = f'''<div class="connection-graphic"><p>{e(d['caption'])}</p><dl>{''.join(f"<div><dt>{e(case['condition'])}</dt><dd>{e(case['result'])}</dd></div>" for case in d['cases'])}</dl></div>''' if d else ''
        visible_limit = f'<p class="notice">{e(f["visible_limit"])}</p>' if f.get('visible_limit') else ''
        lead = f'''<section class="lead"><div class="lead-copy">{subject_category(f)}<h2>{e(f['display_title'])}</h2><p>{e(f['summary'])}</p>{visible_limit}<p class="origin">{e(f['origin'])}</p></div>{graphic}<div class="lead-evidence">{evidence(f)}</div></section>'''
    more_subjects = f'<section class="app-subjects"><h2>More selected capabilities.</h2><div class="subject-grid">{"".join(subject_story(f) for f in standards)}</div></section>' if standards else ''
    lead_band = '<div class="landscape-band" aria-hidden="true"></div>' if lead else ''
    version = f"{e(data['release'])} · {e(data['release_status'])} · Pilot"
    if layout == 'app':
        content = f'''<header class="intro"><p class="status">{version}</p><h1>{e(data['app_headline'])}</h1><p>Selected capabilities present in this source—not just changes introduced in this release.</p><p><a href="{REPO}/tree/{data['source']}">Source snapshot: {data['source'][:7]}</a></p></header>{lead}{lead_band}{more_subjects}'''
    elif layout == 'release':
        content = f'''<header class="intro release-intro"><p class="status">{e(data['release_status'])} · {e(data['release_base_tag'])} → {e(data['release'])} · Pilot</p><h1>{e(data['release_headline'])}</h1><p>{len(data['release_changes'])} selected changes in {e(data['release'])}; not a complete list.</p></header><section class="release-stories" aria-label="Selected changes">{''.join(release_story(c) for c in data['release_changes'])}</section><div class="landscape-band" aria-hidden="true"></div><section class="accounting"><div><h2>What this comparison covers.</h2><p>{e(data['release_base_tag'])} → {e(data['release'])}</p></div><div><p class="numbers"><strong>{added} added / {removed} removed</strong></p><p>Approximate production-path source lines; tests excluded. Not a measure of quality or effort.</p><p><a href="{REPO}/compare/{data['release_base']}...{data['source']}">Source diff <span class="sr-only">from {data['release_base']} to {data['source']}</span></a> · {data['release_base'][:7]} → {data['source'][:7]}</p><details><summary>How these lines were counted</summary><div class="detail-body"><p>Paths are classified by heuristic into production, tests, translations, generated and other. This final tree difference is not a measure of effort or quality.</p><p><a href="{REPO}/compare/{data['release_base']}...{data['source']}">View the source comparison</a> · {data['source'][:7]}</p></div></details></div></section>'''
    else:
        raise ValueError('Unknown view')
    return f'''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Collie · How the app is built</title><style>
@font-face{{font-family:Manrope;src:url(data:font/ttf;base64,{manrope}) format("truetype");font-weight:200 800}}@font-face{{font-family:Sora;src:url(data:font/ttf;base64,{sora}) format("truetype");font-weight:100 800}}
*{{box-sizing:border-box}}:root{{color-scheme:light dark;--page:#f9fbf4;--ink:#24130d;--muted:#5a4033;--link:#2f4f3a;--rule:#aab69c;--soft:#edf2e4;--forest:#2f4f3a;--gold:#f2982b}}body{{margin:0;background:var(--page);color:var(--ink);font:400 1.0625rem/1.55 Manrope,sans-serif}}a{{color:var(--link);text-underline-offset:.2em}}.shell{{max-width:1160px;margin:auto;padding-inline:clamp(20px,4vw,40px)}}.top{{background:#24130d;color:#edf2e4}}.top nav{{display:flex;align-items:center;gap:1.5rem;min-height:72px;flex-wrap:wrap}}.top a{{color:inherit}}.brand{{display:flex;align-items:center;gap:.65rem;margin-right:auto;text-decoration:none;font:600 1.15rem Sora,sans-serif}}.brand img{{width:38px;height:38px}}.top [aria-current]{{color:#f9c783;text-decoration-thickness:2px}}.skip{{position:absolute;left:-9999px;top:1rem}}.skip:focus{{left:1rem;z-index:2;background:#f2982b;color:#24130d;padding:.5rem}}h1,h2,h3{{font-family:Sora,sans-serif;font-weight:400;line-height:1.13;letter-spacing:-.025em;text-wrap:balance}}h1{{font-size:clamp(2rem,4vw,3rem);max-width:23ch;margin:.4em 0}}h2{{font-size:clamp(1.55rem,2.7vw,2rem);margin:.3em 0}}h3{{font-size:1.35rem}}p{{max-width:65ch}}.status,.origin,.category{{font-size:.875rem}}.status{{font-variant-numeric:tabular-nums;color:var(--muted)}}.intro{{padding:2.5rem 0 1.5rem;max-width:56rem}}.intro>p:not(.status){{font-size:1.125rem;max-width:58ch}}.release-intro{{position:relative;max-width:none;background:#24130d;color:#edf2e4;padding:clamp(1.5rem,4vw,3rem);margin-top:2rem;overflow:hidden;isolation:isolate}}.release-intro::after{{content:"";position:absolute;z-index:-1;right:0;bottom:0;width:48%;height:100%;background:url(data:image/svg+xml;base64,{artwork}) center/100% 100% no-repeat}}.release-intro .status{{color:#dfe9cc}}.category{{font-weight:700;color:#96390c;display:flex;align-items:center;gap:.5rem}}.category-icon{{width:1.4rem;height:1.4rem;flex:none}}.origin{{color:var(--muted)}}.release-stories{{position:relative;display:grid;gap:2rem;padding:1rem 0 2rem}}.landscape-band{{height:96px;background-image:url(data:image/svg+xml;base64,{artwork});background-size:100% 100%;background-position:center;background-repeat:no-repeat}}.release-story{{display:grid;grid-template-columns:1.15fr 1fr;grid-template-areas:"visual copy" "visual evidence";column-gap:clamp(1.5rem,4vw,4rem);align-items:start}}.release-story:nth-child(even){{grid-template-columns:1fr 1.15fr;grid-template-areas:"copy visual" "evidence visual"}}.visual{{grid-area:visual;min-width:0}}.story-copy{{grid-area:copy;background:var(--page);padding:1rem 1.25rem 0}}.story-details{{grid-area:evidence;background:var(--page);padding:0 1.25rem 1rem}}.diagram{{margin:0;min-height:235px;background:var(--soft);padding:1.4rem;display:flex;flex-direction:column;justify-content:center;color:#24130d}}.action-bar{{background:#24130d;color:#edf2e4;padding:.6rem 1rem;font-weight:700}}.clearance-space{{border-left:3px solid #d85b09;margin-left:1rem;padding:.55rem 1rem;color:#6f270f;font-size:.875rem;font-weight:700}}.password-form{{border:2px solid #2f4f3a;padding:1rem;background:#f9fbf4;font-weight:600}}figcaption{{font-size:.8rem;color:#5a4033;margin-top:1rem}}.nav-path{{display:flex;align-items:center;gap:1rem;flex-wrap:wrap;font-size:1.2rem}}.nav-path strong{{background:#dfe9cc;padding:.75rem 1rem}}.navigation>p{{color:#6f270f;font-size:.875rem;font-weight:700}}.lead{{background:#2f4f3a;color:#edf2e4;display:grid;grid-template-columns:1fr 1fr;grid-template-areas:"copy graphic" "evidence graphic";gap:1rem 2rem;padding:clamp(1.5rem,4vw,3rem)}}.lead-copy{{grid-area:copy}}.lead-evidence{{grid-area:evidence}}.lead a{{color:#edf2e4}}.lead .category{{color:#f9c783}}.lead .origin{{color:#dfe9cc}}.notice{{font-weight:700;font-size:.9rem;color:#f9e4c7}}.connection-graphic{{grid-area:graphic;display:flex;flex-direction:column;justify-content:center;gap:.5rem;padding:1rem}}.connection-graphic>p{{font-size:.9rem;font-weight:700;color:#f9e4c7}}.connection-graphic dl{{width:100%;margin:0}}.connection-graphic dl div{{display:grid;grid-template-columns:1fr 1fr;gap:1rem;padding:1rem 0;border-top:1px solid #cbd8b9}}.connection-graphic dt{{color:#dfe9cc}}.connection-graphic dd{{margin:0;font-weight:700}}.app-subjects{{padding:4rem 0}}.subject-grid{{display:grid;grid-template-columns:1fr 1fr;gap:3rem}}.subject{{border-top:1px solid var(--rule);padding:1rem 0}}details{{margin-top:1rem}}summary{{cursor:pointer;font-weight:700;padding:.7rem 0;min-height:44px}}.detail-body{{border-left:1px solid currentColor;padding-left:1rem}}.evidence-links{{display:flex;flex-wrap:wrap;gap:.75rem 1.25rem}}.accounting{{border-top:1px solid var(--rule);display:grid;grid-template-columns:1fr 1fr;gap:2rem;padding:3rem 0}}.numbers{{font-size:1.4rem;font-variant-numeric:tabular-nums}}.closing{{border-top:1px solid var(--rule);padding:2rem 0;display:flex;gap:1.25rem;align-items:center}}.closing h2{{font-size:1.35rem}}.closing img{{width:60px;height:60px;border:1px dashed #d85b09;border-radius:50%;padding:12px}}footer{{padding:1.5rem 0;border-top:1px solid var(--rule);color:var(--muted);font-size:.9rem}}.sr-only{{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap}}:focus-visible{{outline:3px solid #f2982b;outline-offset:4px}}::selection{{background:#2f4f3a;color:#edf2e4}}@media(max-width:650px){{.top nav{{gap:.8rem;padding-block:.8rem}}.brand{{width:100%}}.release-story,.lead,.accounting,.subject-grid{{grid-template-columns:1fr;gap:1rem}}.story-copy,.story-details{{padding:0}}.release-stories{{gap:2rem}}.release-story,.release-story:nth-child(even){{grid-template-columns:1fr;grid-template-areas:"copy" "visual" "evidence";gap:.75rem}}.landscape-band{{height:55px}}.intro{{padding:2rem 0 1rem}}.release-intro{{padding:1.5rem;margin-top:1.5rem}}.release-intro::after{{display:none}}.lead{{grid-template-areas:"copy" "graphic" "evidence"}}.connection-graphic{{padding:0}}.app-subjects{{padding:3rem 0}}.detail-body{{padding-left:0;border:0}}.closing{{align-items:flex-start}}}}@media(prefers-color-scheme:dark){{:root{{--page:#24130d;--ink:#edf2e4;--muted:#c9d5b3;--link:#dfe9cc;--rule:#687e59;--soft:#dfe9cc}}.category{{color:#f3b884}}}}@media(forced-colors:active){{.landscape-band{{background-image:none}}.lead,.diagram,.password-form,.action-bar{{border:1px solid CanvasText;forced-color-adjust:auto}}.connection-graphic dl div{{border-top:1px solid CanvasText}}}}
</style></head><body><a class="skip" href="#main">Skip to content</a><header class="top"><nav class="shell" aria-label="Explore Collie"><a class="brand" href="app.html"><img src="data:image/svg+xml;base64,{mark}" alt="">Collie</a><a href="release.html" {'aria-current="page"' if layout=='release' else ''}>This release</a><a href="app.html" {'aria-current="page"' if layout=='app' else ''}>Across the app</a></nav></header><main class="shell" id="main">{content}<section class="closing"><img src="data:image/svg+xml;base64,{mark}" alt=""><div><h2>Built on Signal and Molly. Adapted for Collie.</h2><p>Follow the source behind the choices described here.</p><p><a href="https://github.com/signalapp/Signal-Android">Signal</a> · <a href="https://github.com/mollyim/mollyim-android">Molly</a></p></div></section><footer><p>If Collie is useful to you, you can <a href="https://collie.im/#donate-title">support its development</a>.</p><p>{'Experimental pilot · selected capabilities in this source snapshot.' if layout == 'app' else 'Experimental pilot · selected changes, not a complete release list.'}</p></footer></main></body></html>'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--layout', choices=['app', 'release'], required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    receipt = json.loads(args.receipt.read_text(encoding='utf-8'))
    args.out.write_text(render(receipt, args.layout), encoding='utf-8')


if __name__ == '__main__':
    main()
