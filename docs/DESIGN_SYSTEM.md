# SAMAVETA — DESIGN SYSTEM

**Direction:** restrained, warm, typography-led. Two neutrals plus one accent. No purple gradients,
no glowing orbs, no decorative animation, no generically "AI-looking" imagery. The interface should
read as a considered instrument, not a chatbot.

## 1. Palette

| Role | Light | Dark | Notes |
|---|---|---|---|
| Canvas (background) | `#FAF8F5` warm off-white | `#12100E` near-black charcoal | Not pure white/black: avoids harshness and OLED halo |
| Surface (raised) | `#FFFDFA` | `#1C1917` | Sheets, composer, grouped blocks |
| Surface elevated | `#F2EDE6` | `#262220` | Inputs, selected chips |
| Border | `#E4DED5` | `#2E2A26` | 0.7 dp hairline |
| Border strong | `#CFC7BB` | `#443E38` | Outline/focus |
| Primary text | `#1A1815` | `#F3EFE9` | Warm charcoal / warm white |
| Secondary text | `#5C564E` | `#B3ACA2` | Body-supporting copy |
| Tertiary text | `#8C857B` | `#7C766D` | Metadata, captions |
| **Accent** | `#B06A3B` copper | `#D0824F` copper | The only chromatic colour |
| Accent container | `#F3E3D6` | `#3A2417` | Selected states |
| Success | `#3F6B4A` | `#8FBF9B` | Verification confirmed |
| Caution | `#8A6A1F` | `#D9BC72` | Unverified/degraded — used a lot, honestly |
| Danger | `#8E3B33` | `#DD9A92` | Failure, emergency stop |

**Accent discipline.** Copper appears only for: active navigation, primary action, focus ring, the
"live" dot, and brand marks. Everything else is neutral. This is what makes the accent read as
intentional rather than decorative.

**Colour is never the only signal.** Selected nav items also get an underline; status rows combine
colour with a label ("Unconfirmed", "LAYA not connected").

## 2. Typography

One sans-serif family (system default) with weight and size hierarchy. Custom fonts were rejected:
they add APK size and complicate Devanagari and CJK fallback.

| Token | Size / line height | Weight | Use |
|---|---|---|---|
| `display` | 34 / 42, −0.5 tracking | Light | Screen titles only |
| `title` | 22 / 28 | Medium | Brand block, major headings |
| `heading` | 17 / 24 | Medium | Section and card headings |
| `body` | 15 / 23 | Normal | Default copy |
| `bodyEmphasis` | 15 / 23 | Medium | Row titles, actions |
| `caption` | 12.5 / 17 | Normal | Metadata, provenance, limitations |
| `label` | 11 / 14, 0.9 tracking | Medium | Uppercase section labels, status pills |
| `mono` | 12.5 / 18 | Normal | Paths, ids, diagnostics |
| `brandDevanagari` | 18 / 28 | Normal | समवेत — generous leading so matras are not clipped |

## 3. Space and shape

Scale (dp): `2, 4, 8, 12, 16, 24, 32, 48`; page gutter `20`.
Radii: `sm 6`, `md 10`, `lg 14`, `pill 999`. Small radii on purpose — large radii read as generic.

## 4. Composition rules

1. **No card grids.** Content sits on the canvas separated by 0.7 dp hairlines. Cards are reserved
   for grouped settings/detail (`SurfaceBlock`).
2. **No bubbles.** Agent messages are full-width text blocks labelled "You" / "SAMAVETA". Bubbles
   waste width and make long model output unreadable.
3. **One primary action per screen.**
4. **Every list has a designed empty state** that explains why it is empty and what will change it.
5. **Loading uses skeletons, not spinners**, except for a single in-progress task indicator.
6. **Errors are specific and actionable** — never "Something went wrong" alone.
7. **Motion is functional**: state transitions and streaming text only; nothing loops decoratively
   except the skeleton shimmer and the pending-task spinner.

## 5. Accessibility

- Minimum 48 dp touch targets for taps; 20 dp icons with 24 dp boxes.
- Body text 15 sp with 23 sp leading; supports font scaling to 200% (unverified on device).
- Every icon has a content description; decorative icons pass `null`.
- Contrast targets WCAG AA for body text in both themes.
- Theme follows the system by default; no hard-coded light/dark assumptions in components.

## 6. Brand

- Wordmark: **SAMAVETA**, with **समवेत** set in Devanagari wherever the full identity appears — never
  transliterated.
- Tagline: *Intelligence, United.*
- Release mark: **SANTOOR** — a trapezoid dulcimer body, two bridges and seven strings, drawn on a
  24 dp grid at 1.5 stroke (`res/drawable/ic_santoor.xml`), reused as the adaptive launcher icon
  foreground and the splash icon.
- Status bar and navigation bar are transparent; icon appearance is derived from the active theme.

## 7. Component inventory

`PageScaffold`, `SectionHeader`, `HairlineDivider`, `SurfaceBlock`, `EmptyState`, `ErrorState`,
`SkeletonBlock`, `StatusPill`, `AccessibleIcon`, `BottomBar`/`BarItem`, `ThemeSelector`, `ToggleRow`,
plus the Agent composer and turn blocks. Screens compose these rather than rolling their own, which
is what keeps ten features looking like one product.
