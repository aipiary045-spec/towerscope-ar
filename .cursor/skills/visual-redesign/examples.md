# Prompt variants

Use [kickoff-prompt.md](kickoff-prompt.md) for a full identity change. Use these when the job is smaller.

## Theme only (explicit opt-out)

```
Theme only. Keep every layout file. Swap day / night / high-contrast tokens in colors.xml
and values-night/colors.xml. Do not change activity_home.xml structure.
Outdoor contrast still required. No pastel text on pale blue.
```

## Match this screenshot

```
Match this screenshot’s layout and type. Ignore our current theme.
Banned: Inter, indigo, rounded cards in a 2-column grid, generic FABs,
hero logo card, icon-in-circle hub buttons.
Change structure, not just colors.
Then screenshot Home, Network Hub, and Compass against the reference.
```

## Best of N / parallel agents

Give each agent a **different** brief. Same features. Isolated worktrees.

```
You have creative freedom on visual language.
Hard constraints: keep these features, these navigation paths, and outdoor readability.
Do not preserve the current card grid.
Brief for this run: {dense instrument HUD | print-shop / field clipboard | editorial / magazine, almost no cards}.
Show 3 mockups of Home + Compass, then implement only this brief.
```

Judge from screenshots. Apply only the winner.

## Polish after the direction is locked

Android: paste an emulator screenshot, then:

```
Fix this region only. Match the mock we already picked.
Do not restyle unrelated screens. Do not revert to rounded hub tiles.
```

Web apps (not this repo): Design Mode (Cmd+Shift+D) for spacing / “make this match that.”
