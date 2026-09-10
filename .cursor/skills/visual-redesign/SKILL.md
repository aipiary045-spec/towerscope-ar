---
name: visual-redesign
description: Redesign TowerScope / WispEaze UI by changing layout, type, density, and motion — not a colors.xml swap. Use when the user asks for a visual redesign, new look, unique UI, make it look different, stop looking like SaaS, Plan Mode visual directions, mockups, Best of N design, or /visual-redesign.
---

# Visual Redesign

TowerScope is an Android field toolkit (Kotlin + XML). Models default to a generic SaaS look: cards, blue accents, Inter-ish type, padded tiles. “Change it up completely” still reads as restyle this, so you get a palette swap on the same layout.

This skill is the playbook. Do not skip steps. Do not start in `colors.xml`.

## Hard split

**Wide (freedom):** Plan Mode, 3 directions, mockups, Best of N / parallel agents, different briefs. Let it be weird.

**Narrow (quality):** keep features and view IDs, outdoor readability, reuse `Widget.TowerScope.Button.*`, verify with screenshots. See `.cursor/rules/field-app-quality.mdc`.

Creative freedom is for visual language. Hard constraints are navigation, Kotlin logic, and sun/glove readability.

## Current look (abandon this)

Home and both hubs are the same recipe: greeting, logo hero card, two rounded tiles, another tile, 4-tab bottom nav. Hubs are 2-column grids of `item_hub_tool_row` (icon-in-circle + title + subtitle on `bg_nav_tile`, 24dp corners). Tokens: cool slate + electric blue (`#2A5BE0` / `#2F6BFF`). Type: Source Sans 3 + monospace metrics.

Recoloring that **fails** this skill.

Full inventory: [current-look.md](current-look.md)

## Process — do not skip

Copy this checklist:

```
Visual redesign:
- [ ] Inventory tokens, layouts, and AI defaults in use
- [ ] References attached, or generate mockups (do not guess adjectives)
- [ ] 3 directions that differ in LAYOUT, not palette — wait for a pick
      (or run Best of N / parallel agents with different briefs)
- [ ] After pick: 3 full-screen mockups of the chosen direction — wait again
- [ ] Implement: layout XML, type, spacing, drawables, custom views
- [ ] Screenshot vs reference. Name the structural change per screen.
- [ ] If it still looks like a template, STOP and try another direction
```

### 1. Inventory (Ask-style)

List what is generic before proposing anything: card drawables, hub grid, hero card, bottom nav chrome, font, accent names. Do not write production layouts yet.

### 2. Pixels, not adjectives

If the user attached a screenshot / Garmin / DJI / Fluke / radio / rangefinder / magazine layout: **match that structure**.

If they did not: generate 2–3 mockups and **wait**. Do not implement from “make it unique.”

Banned as the only change: Inter, indigo, rounded card grids, generic FABs, glassmorphism, neumorphism, “modern/clean.”

### 3. Three directions, then stop

Propose 3 named directions that differ in **layout, type, density, motion**, and what you will **not** reuse from the current UI. Example briefs that actually diverge:

- Dense instrument HUD (rangefinder overlay, huge live numbers)
- Print-shop / field clipboard (stamped vinyl, checklists, almost no cards)
- Editorial / magazine (asymmetric type, almost no tiles)

Wait for a pick. Exception: user said to run Best of N / parallel agents — then isolate each brief in its own worktree and let them judge from screenshots.

### 4. Implement the winner

Must change layout XML, type, spacing, drawables, and custom views if a gauge/HUD needs one. `colors.xml` alone is a failed job.

Rebuild these to match the mockups, **not** the old structure:

- `app/src/main/res/layout/activity_home.xml`
- `app/src/main/res/layout/activity_network_hub.xml`
- `app/src/main/res/layout/activity_installation_hub.xml`
- `app/src/main/res/layout/include_bottom_nav.xml`
- `app/src/main/res/layout/item_hub_tool_row.xml`
- `app/src/main/res/layout/activity_main.xml` (compass)
- `app/src/main/res/layout/activity_map.xml`
- `app/src/main/res/layout/activity_wifi_monitor.xml`
- `app/src/main/res/layout/activity_speed_test.xml`
- `app/src/main/res/layout/bottom_sheet_settings.xml`

When a screen is done, name what structurally changed vs the old card layout. If you cannot name a structural change, redo that screen.

Keep Kotlin logic, ViewModels, parsers, location, compass, site import. Keep `android:id` values activities bind, or update the Kotlin in the same change.

Stack: Kotlin + XML, `minSdk 33`, package `com.towerscope.ar`. Do not add Compose unless you justify one screen.

### 5. Verify with pictures

This is Android XML. Cursor Design Mode will not drive the emulator.

Loop: emulator or device screenshot → compare to mock/reference → fix the **region**, not the whole theme.

Motion only where it means something (compass, signal, LOS). No decorative fade-ins.

### 6. New chat when stuck

If the thread is long or the agent keeps making the same mistake: new conversation, `@` the plan / this skill. Do not stack “try again” prompts on the same card layout. Revert and rebuild from the chosen direction.

## Copy-paste kickoff

User-facing first message (Plan Mode): [kickoff-prompt.md](kickoff-prompt.md)

Shorter variants: [examples.md](examples.md)

## Optional Cursor User Rule

Paste into **Cursor Settings → Rules** (all projects) if they want this habit everywhere. Do **not** put this in always-on project rules — it locks taste.

```
When I ask for a UI change, do not only recolor.
Change layout, typography, density, and motion unless I say "theme only."
If I didn't attach a visual reference, generate 2 mockups and wait.
Banned defaults: Inter, indigo-600, rounded-2xl card grids, generic hero + 3 features.
```

## Success

A tech who already used the app needs a second to reorient because the chrome is new — then finds every tool faster.

If someone could screenshot Home and say “same app, new colors,” you failed.
