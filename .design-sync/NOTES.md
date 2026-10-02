# Design sync notes

- This repo has no web component library (no package.json, no Storybook): the Validatopia UI is
  Jetpack Compose and SwiftUI. So the Claude Design project is **tokens only**, chosen by the
  user on 2026-10-02: colours, type scale, card-art colours, brand guidelines, logos and preview
  cards. It is built off-script by `.design-sync/build-tokens.py`, not by the design-sync
  converter, so there's no `_ds_bundle.js`, no components and no `_ds_sync.json` anchor; every
  re-sync re-uploads everything, which is a dozen files.
- Tokens are parsed from `samples/validatopia/shared/src/commonMain/kotlin/.../branding/`
  (`ValidatopiaColors.kt`, `ValidatopiaTypography.kt`, `ValidatopiaCardArt.kt`). Change the
  colours there, not in the CSS, then rebuild.
- `styles.css`, `guidelines/*.html` (except `colors.html`, which the script generates) and
  `guidelines/brand.md` are sources in `.design-sync/src/`. Logos are copied from the apps'
  resources at build time.
- Preview cards set `data-theme="light"`: a browser in dark mode otherwise switches the tokens to
  the dark scheme, and the colour card's labels no longer match its swatches.
- Both apps use the platform's system font, so there are no font files.
