# collie.im website

One static page that explains Collie and links to the downloads. The source is in
this folder, `tooling/site/build.py` builds it, and `.github/workflows/site.yml`
deploys it to GitHub Pages at https://collie.im.

## Build and preview locally

```sh
python3 tooling/site/build.py --out /path/to/out              # uses the latest GitHub release
python3 tooling/site/build.py --out /path/to/out --tag v8.35.1-3-rc2 --roadmap-json /path/to/discussions.json  # offline
cd /path/to/out && python3 -m http.server 8000
```

The build copies this folder (except Markdown files), fills `{{RELEASE_TAG}}` and
`{{APK_URL}}` in `index.html`, and fails if a placeholder or a local file is
missing. No Python packages are needed.

## When it deploys

- Every push to `main` that changes `docs/site/`, `tooling/site/` or the workflow.
- Daily at 06:17 UTC, so the "Latest release" line follows new releases.
- By hand: Actions → Website → Run workflow.

Pull requests only build. The APK button always points at
`releases/latest/download/collie.apk`, so it never lags behind a release.

## One-time setup (repository owner)

1. DNS for `collie.im`: apex `A` records `185.199.108.153`, `185.199.109.153`,
   `185.199.110.153`, `185.199.111.153`; apex `AAAA` records `2606:50c0:8000::153`,
   `2606:50c0:8001::153`, `2606:50c0:8002::153`, `2606:50c0:8003::153`;
   `www` `CNAME` `0cwa.github.io`.
2. Pages: Settings → Pages → Source "GitHub Actions", custom domain `collie.im`,
   then tick "Enforce HTTPS" once the certificate is issued.

## Design

- Palette: Bark Black `#24130D` and the Redwood darks for the hero and footer,
  Golden orange `#F2982B` for the main buttons, Forest `#2F4F3A` for the build
  section, links and focus, Lichen Mist `#EDF2E4` for repeated cards, Soft Lichen
  `#DFE9CC` for the featured card and the download block. Parchment appears only
  inside the logo. Dark mode follows the visitor's system setting.
- Type: Manrope (weights 250–500) for body text and the headline, Sora (200–300)
  for subheadings.
- The dashed ring from the logo is the recurring shape: behind the hero phones,
  on the featured card, around the dog in the download block, and as the line
  down the patch stack.

## Assets and where they come from

| File | Source |
|---|---|
| `assets/fonts/manrope.ttf`, `sora.ttf` and their `OFL.txt` | google/fonts at commit `b31870aff700ab7a1d74fa0c6887d95beb9e0037`, the same files repo.collie.im pins |
| `assets/collie-icon.svg`, `collie-foreground.svg` | `docs/assets/` unchanged |
| `assets/og.png` | `docs/assets/collie-feature-graphic.svg` rendered at 1200 px wide with `rsvg-convert` |
| `assets/screens/conversation.webp` | Native API35 offline demo with fictional Alex/six messages, now Fastlane 1.png; 1080×2160 resized to 540×1080 with `cwebp -q 82`; [capture provenance](../screenshots/pr-95/conversation-capture.md) |
| `assets/screens/encryption.webp` | Original corrected 1080×2400 API35 capture in immutable `555fff83a6b7e8f5b8de89f522859f250a166b5f`, resized to 540×1200; current Fastlane 3.png is a separate native 1080×2160 capture for its 2:1 limit |
| `assets/screens/locked.webp`, `proxy.webp` | Existing Fastlane locked (formerly 4.png, now 2.png) and proxy (6.png) captures, resized to 540 px wide with `cwebp -q 82`; proxy retained but no longer a hero phone |

## Upcoming features

The page is generated from the repository's **Upcoming/Planned Features**
Discussion category (`upcoming-planned-features`). Maintain titles and plans in
those discussions. Moving a discussion into or out of that category adds or
removes it on the next build. Discussions labelled `priority` appear first.
Feature requests in other categories do not appear as promised work.

The daily build reads GitHub's Discussions API with `discussions: read`. A failed
API request fails the build and leaves the last deployed site intact. Local
builds use `GITHUB_TOKEN` (or `GH_TOKEN`), or `--roadmap-json` for an offline JSON
array of discussion nodes with `number`, `title`, `category.slug`, and optional
`labels.nodes` fields. Titles are escaped before rendering. The site links to the
source discussions and makes clear that planned features are not yet available.

Run `python3 -m unittest discover -s tooling/site -p 'test_*.py' -v` to check
category filtering, priority ordering, title escaping, API pagination/failures,
output-directory safety and the built page.

## Obtainium links

Use `https://collie.im/obtainium/` for the website button and README badge.
The Collie-hosted handoff page opens the import configuration from
`tooling/site/obtainium.json`. Its APK update source stays on GitHub; the manual
installation link points to `https://collie.im/#get`. This avoids depending on
the external redirect page's fallback text and its assumption that the update
source is the app's home page.
