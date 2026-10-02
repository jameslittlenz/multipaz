# Validatopia design tokens

Validatopia is a fictional country whose digital ID wallet and verifier apps (Android, iOS) this
system brands. **There are no components here, only tokens.** Build your own UI with plain HTML
and CSS, styled only through the CSS custom properties below. Never hard-code a colour.

## Setup
Link `styles.css`: it imports `tokens/colors.css` and `tokens/typography.css`, and sets the
body's font, size and colours. Dark mode follows the system (`prefers-color-scheme`); force it
with `<html data-theme="dark">`, or keep light with `data-theme="light"`.

## Colour: `var(--vt-color-<role>)`
Material 3 roles, each with an `on-` partner for text on it:
`primary`/`on-primary`, `primary-container`/`on-primary-container`, `secondary`/`on-secondary`,
`secondary-container`/`on-secondary-container`, `background`/`on-background`,
`surface`/`on-surface`, `surface-variant`/`on-surface-variant`, `surface-container`, `outline`,
`error`/`on-error`, `warning-container`/`on-warning-container`.
Status text and icons: `success`, `warning`, `neutral`, `error`.
Brand constants: `--vt-brand-navy` (#0A1B37), `--vt-brand-green` (#4EBC7D), `--vt-brand-teal` (#0E7C7B).

Rules (WCAG 2.2 AA):
- Green on white is 2.4:1. In light mode green is only a **fill** (`secondary`) with navy content
  (`on-secondary`), never text or an icon on white. For "success" text use `--vt-color-success`.
- Never show status by colour alone: pair it with an icon and a word ("Trusted", "Not shared").
- Use only the role pairs above for text on a fill; they're all checked for contrast.

## Type: `var(--vt-type-<style>-size)` and `-line-height`
Styles: `display-large` 57/64, `headline-medium` 28/36, `title-large` 22/28, `body-large` 16/24
(default), `body-medium` 14/20, `label-large` 14/20. Font: `var(--vt-font-family)`, the system
font (SF on iOS, Roboto on Android). Bold weights only for emphasis and buttons.

## Document card art
Each document is a 340x215 card (radius 16px): a solid background, three translucent hills along
the bottom (`--vt-card-hill-1-opacity` to `-3-`), a white title naming the type ("Photo **ID**",
the last word bold) and a subtitle. Tokens: `--vt-card-<doc>-background|hills|title|subtitle` for
`photo-id`, `driver-licence`, `gym-membership`, `age-verification`, `unknown`.
See `guidelines/card-art.html`.

## Where the truth lives
`tokens/colors.css`, `tokens/typography.css`, `guidelines/brand.md`, and the cards in
`guidelines/` (colours, type, card art, logos in `guidelines/assets/`).

## Example
```html
<link rel="stylesheet" href="styles.css">
<main style="padding:16px;display:grid;gap:12px">
  <h1 style="font-size:var(--vt-type-headline-medium-size);line-height:var(--vt-type-headline-medium-line-height);margin:0">Your Photo ID</h1>
  <p style="color:var(--vt-color-success);margin:0">&#10003; Trusted issuer</p>
  <button style="background:var(--vt-color-primary);color:var(--vt-color-on-primary);border:0;border-radius:24px;padding:14px 24px;font:inherit;font-weight:600">Share</button>
  <button style="background:var(--vt-color-secondary);color:var(--vt-color-on-secondary);border:0;border-radius:24px;padding:14px 24px;font:inherit;font-weight:600">Verify with passport</button>
</main>
```
