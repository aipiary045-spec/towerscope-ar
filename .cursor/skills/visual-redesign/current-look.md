# Current look inventory

Use this when starting a redesign. These are the defaults to **not** remix.

## Layout recipe

| Screen | Structure |
|--------|-----------|
| Home | Greeting + sub → logo hero on `bg_nav_tile` + `bg_signal_rings` → 2 hub tiles (`bg_job_icon` circles) → settings row → 4-tab bottom nav |
| Network Hub | Eyebrow + title + subtitle → decorative rings card → 2-column `item_hub_tool_row` grid (Wi-Fi, Speed, Ping, Subnet, DNS, Trace, Bandwidth, Path Doctor) |
| Installation Hub | Same recipe → Compass, Locate, LOS, Sites import |
| Tool rows | Centered icon-in-circle (52dp) + bold title + 11sp subtitle, `minHeight` 132dp, 24dp rounded tile |
| Bottom nav | 4 equal columns: Home / Network / Install / Settings, 10sp labels, teal active tint |
| Compass | HUD chips + `CompassRadarView` + primary buttons in a bottom panel |
| Settings | Accordion bottom sheet (`bottom_sheet_settings.xml`) |

## Tokens

- Light: `colors.xml` — cool slate backgrounds (`#E8EEF8`), electric blue accent (`accent_yellow` = `#2A5BE0`), teal secondary (`#0E7A9A`)
- Night: `values-night/colors.xml` — navy `#070B16`, electric blue `#2F6BFF`, cyan `#4CC9FF`
- Names are leftover from an older gold/forest theme (`accent_yellow`, `forest`, `sage`) but resolve to blue
- Radii: 12 / 16 / 20 / 24dp (`dimens.xml`)
- Type: `@font/source_sans3_*` everywhere; metrics use `monospace`
- Cards: `bg_nav_tile`, `bg_field_card`, `bg_metric_tile`, `bg_mode_card`, `bg_network_card`

## AI defaults in use

- Hero + 2 feature tiles + settings row
- Rounded Material cards as primary chrome
- Icon-in-circle hub buttons
- Generic 4-tab bottom nav
- Startup-app blue palette
- Source Sans as the personality font (readable, not distinctive)

## Must keep working (not visual)

- Home → Network Hub, Installation Hub, Settings
- Network tools: Wi-Fi, Speed test, Ping, Path Doctor, Subnet, DNS, Traceroute, Bandwidth
- Install tools: Compass, Locate map, LOS / Fresnel, site import (KML/KMZ/CSV)
- Day / night / high-contrast themes
- Kotlin ViewModels, parsers, location, compass math
