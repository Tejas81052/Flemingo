# thimmaiah.me

Personal portfolio site for Tejas Thimmaiah and the public-facing
docs for the **Ebors** Android browser.

## Deploy

Static HTML/CSS only — no build step. Drop the contents of this
folder on any static host:

- **GitHub Pages** — push to a `gh-pages` branch or a `docs/` folder
  on `main`, point your domain `thimmaiah.me` at it in DNS, and
  Pages handles HTTPS via Let's Encrypt automatically.
- **Cloudflare Pages** — connect this repo, set output dir to
  `website/`, and add `thimmaiah.me` as a custom domain.
- **Netlify** — same shape; `website/` is the publish directory.

## Files

| Path | Purpose |
|---|---|
| `index.html` | Portfolio home — About, Projects, Achievements, Contact |
| `applicencing.html` | App licensing & attributions for Ebors (linked from inside the app) |
| `privacy.html` | Privacy policy required by Google Play |
| `terms.html` | Terms of use |
| `assets/style.css` | Single stylesheet, paper theme with prefers-color-scheme: dark |
| `assets/favicon.svg` | Site icon |

## Editing

Each page is plain HTML. Sections in `index.html` are clearly
commented — the Achievements grid in particular is set up as
repeatable `<article class="achievement">` blocks; copy/paste an
existing one to add a new entry.

The Ebors in-app "Open-source licenses" screen at
`app/src/main/assets/open_source_licenses.html` is the bundled
mirror of `applicencing.html` — keep them roughly in sync if you
edit either.
