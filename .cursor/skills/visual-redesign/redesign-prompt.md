# Starter prompt (paste as the first message)

Use this as a new chat in Plan Mode (Shift+Tab). Paste it as the first message. If you have a screenshot of a radio, rangefinder, Garmin, DJI, Fluke, or any UI you like, attach it. If you don’t, the prompt tells the agent to generate mockups first.

```
Complete visual redesign of TowerScope / WispEaze. Features stay. The current look does not.
This is an Android field toolkit for WISP install techs. Used outdoors, in sun, with gloves. One-handed. Glanceable. Not a SaaS dashboard.
WHAT MUST KEEP WORKING
- Home: Network Hub, Installation Hub, Settings
- Network Hub: Wi-Fi, Speed test, Ping, Path Doctor, Subnet, DNS, Traceroute, Bandwidth
- Installation Hub: Compass, Locate map, LOS / Fresnel profiles, site import (KML/KMZ/CSV)
- Bottom nav, settings sheet, day / night / high-contrast themes
- Existing Kotlin logic, ViewModels, parsers, location, compass. Do not rip out features to make it pretty.
WHAT IS WRONG WITH THE CURRENT UI
Home and both hubs are the same recipe: greeting, logo hero card, two rounded tiles, another tile, bottom nav. Network/Install hubs are titled lists of rounded rows. Colors live in colors.xml as cool slate + electric blue. Type is Source Sans 3. Recoloring that will fail this task.
BANNED (even as a “fresh” version)
- Recoloring colors.xml as the main change
- Hero logo card + 2 hub tiles + settings row
- Rounded Material cards / nav tiles as the primary chrome
- Generic 4-tab bottom nav that looks like every fintech app
- Inter / Roboto / Source Sans as the personality font (keep a readable body face; pair it with a distinct display/data face)
- Indigo / electric-blue startup-app palette
- Centered icon-in-circle hub buttons
- “Make it modern / clean / glassmorphism / neumorphism”
DIRECTION
Instrument HUD + field clipboard. Think rangefinder overlay, radio faceplate, survey controller — not Linear, not Stripe, not Material You.
Hard requirements:
- Information density of a tool, not a marketing site
- Huge live numbers (RSSI, heading, Mbps, clearance) as the first thing the eye hits
- Status as color + shape, readable in sun (not pastel chips)
- Gloves: hit targets ≥ 48dp, primary actions on the thumb edge
- Dark-first outdoor night theme that is actually dark, plus a high-contrast day theme that is not washed grey-blue
- Custom chrome: gauges, tick marks, stamped metal / stamped vinyl / topo engraving — not another rounded rectangle
- Motion only where it means something (compass, signal, LOS). No decorative fade-ins.
PROCESS — do not skip
1. Plan Mode. Propose 3 distinct directions that differ in LAYOUT, not just palette. Name them. For each: Home, one Network tool, Compass. Wait for me to pick. Do not write production layouts yet.
2. After I pick: generate 3 full-screen mockups of the chosen direction (Home, Network Hub, Compass). Wait again.
3. Then implement. You must change layout XML, type, spacing, drawables, and custom views. colors.xml alone is a failed job.
4. Rebuild these screens to match the mockups, not the old structure:
   - activity_home.xml
   - activity_network_hub.xml
   - activity_installation_hub.xml
   - include_bottom_nav.xml
   - activity_main.xml (compass)
   - activity_map.xml
   - activity_wifi_monitor.xml
   - activity_speed_test.xml
   - bottom_sheet_settings.xml
5. When a screen is done, describe what structurally changed vs the old card layout. If you cannot name a structural change, redo that screen.
CONSTRAINTS
- Kotlin + XML. Stay on the current stack unless a custom View is required for a gauge/HUD.
- minSdk 33. Do not add Compose unless you justify one screen.
- Keep package com.towerscope.ar.
- Do not break site import, location permissions, or LOS math.
- Outdoor readability > decoration.
SUCCESS
A tech who already used the app should need a second to reorient because the chrome is new — and then find every tool faster. If someone could screenshot Home and say “same app, new colors,” you failed. Start with the 3 directions.
```

## Shorter “freedom” shape

```
You have creative freedom on visual language. Hard constraints: keep these features, these navigation paths, and outdoor readability. Do not preserve the current card grid. Show me 3 directions; I will pick.
```

## Parallel briefs (Best of N / multiple agents)

Same constraints as above. Different visual brief per agent:

- dense instrument HUD
- print-shop / field clipboard
- editorial / magazine, almost no cards
