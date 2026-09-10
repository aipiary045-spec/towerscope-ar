---
name: visual-redesign
description: Redesign TowerScope / WispEaze visual identity with competing directions, mockups, then layout rebuilds. Use when the user wants a new look, UI variety, visual direction, mockups, Best of N, or says the UI looks the same, generic, AI-default, or only changed colors.
---

# Visual redesign

Cursor defaults to a generic SaaS look. “Change it up completely” still reads as restyle this, so you get a palette swap on the same layout. Treat UI as explore → pick → implement → polish. Do not paint in one chat.

## Current look (do not restyle this)

Home and both hubs are the same recipe: greeting, logo hero card, two rounded tiles, another tile, bottom nav. Network/Install hubs are titled lists of rounded `item_hub_tool_row` tiles (`bg_nav_tile` + icon-in-circle). Colors live in `colors.xml` as cool slate + electric blue (`#2A5BE0` / `#2F6BFF`). Type is Source Sans 3. Recoloring that will fail this task.

## Banned (even as a “fresh” version)

- Recoloring `colors.xml` as the main change
- Hero logo card + 2 hub tiles + settings row
- Rounded Material cards / nav tiles as the primary chrome
- Generic 4-tab bottom nav that looks like every fintech app
- Inter / Roboto / Source Sans as the personality font (keep a readable body face; pair it with a distinct display/data face)
- Indigo / electric-blue startup-app palette
- Centered icon-in-circle hub buttons
- “Make it modern / clean / glassmorphism / neumorphism”

## Process — do not skip

1. **Inventory** tokens, layouts, and AI defaults in use (`colors.xml`, `styles.xml`, `activity_home.xml`, hub layouts, `item_hub_tool_row.xml`, `include_bottom_nav.xml`).
2. **Pixels, not adjectives.** If no screenshot/Figma is attached, generate mockups (or 2–3 reference-style comps) and **wait**. A photo of a rangefinder, radio, Garmin, DJI, Fluke, or field checklist beats “make it unique.”
3. **Propose 3 directions that differ in LAYOUT, not just palette.** Name them. For each: Home, one Network tool, Compass — layout, type, density, motion, and what will **not** be reused. Wait for a pick. Do not write production layouts yet.
   - If the user wants competing implementations instead of a pick: `/best-of-n` or 2–3 isolated agents with different briefs (example: “dense instrument HUD” / “print-shop field clipboard” / “editorial, almost no cards”). Judge from screenshots. Apply only the winner.
4. After a pick: generate 3 full-screen mockups of the chosen direction (Home, Network Hub, Compass). Wait again.
5. **Implement the winner.** Change layout XML, type, spacing, drawables, and custom views. `colors.xml` alone is a failed job. Stay on Kotlin + XML unless a custom View is required for a gauge/HUD.
6. When a screen is done, name the **structural** change vs the old card layout. If you cannot name one, redo that screen.
7. Screenshot and compare to the mockup/reference. If it still looks like a template, stop and try another direction — revert and rebuild from the plan. Do not stack “try again” prompts.

New visual work should be a **new chat**, not the 20th message in the same thread. Switch chats when moving from “invent a look” to “implement the winner.”

## Freedom vs quality

You have creative freedom on visual language.

Hard constraints: keep features, navigation paths, and outdoor readability. Do not preserve the current card grid.

Quality (not aesthetics) lives in project rules: contrast, `@dimen/tap_min` 48dp, reuse `Widget.TowerScope.Button*`, do not rip out Kotlin logic.

TowerScope is Android XML. Design Mode will not drive the emulator. Loop is: emulator screenshot → paste into chat → match this mock / fix this region.

## Screens that must be rebuilt for a full identity pass

- `activity_home.xml`
- `activity_network_hub.xml`
- `activity_installation_hub.xml`
- `include_bottom_nav.xml`
- `activity_main.xml` (compass)
- `activity_map.xml`
- `activity_wifi_monitor.xml`
- `activity_speed_test.xml`
- `bottom_sheet_settings.xml`

## Success

A tech who already used the app should need a second to reorient because the chrome is new — and then find every tool faster. If someone could screenshot Home and say “same app, new colors,” you failed.

## Copy-paste starter

For the full first-message prompt (Plan Mode), see [redesign-prompt.md](redesign-prompt.md).
