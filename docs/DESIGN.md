# Design

How Herdr for Android looks and why. It wears the Herdr desktop's **`workbench`** layout, an
engineering-puzzle-game console, carried over to a phone, and it is laid out as **Shepherdr 3.1's
phone web app**: a drawer of spaces, a bottom bar of Chat, Tasks and Artifacts.

Everything below lives in code in two places, and screens use nothing else:

- `ui/theme/Theme.kt`: colours (`Herdr.colors`, the Material scheme), type (`Sans`, `Mono`,
  `CodeStyle`, `caps()`), spacing (`Space`), corners (`Radius`, all square), frames (`Frame`), the
  terminal's colours.
- `ui/design/`: the components (`Components.kt`, `Chat.kt`, `NavBar.kt`), the workbench's own pieces
  (`Workbench.kt`: glyphs, chip icons, `Led`, `SlotNumber`, `CircuitBackdrop`, from the generated
  `WorkbenchArt.kt`), the icon set (`HerdrIcons.kt`) and `AgentMark`.

A screen that needs a number, a colour or a shape that isn't there adds it here first.

## Credit

The look is the Herdr desktop's `workbench` layout (personal-herdr-desktop, Apache 2.0): its theme
(`herdr-app/themes/workbench.json`), its chip-package icons, dot-matrix glyphs, HERDR chip and
circuit board, which `tools/workbench-art/gen.py` turns into Compose vectors. The faces are Geist and
Geist Mono (SIL OFL 1.1). Icons the workbench has no glyph for are Lucide (ISC). Notices:
[`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).

## Identity: a console on the bench

Near-black panels, amber frames, status lights. Flat and minimal: frames and flat fills only.

1. **Flat.** No gradients, glows, shadows or decorative fills. What floats (a sheet, a dialog, a
   toast) is framed, not lifted. Corners are square everywhere.
2. **Frames, 1dp.** A module (a card, a group of rows, a tile) is a 1dp frame in `lineStrong` on the
   ground. What is in focus or shown is framed in amber: a focused field, a dialog, a sheet's top
   edge, the chosen chip, the drawer's shown slot.
3. **Amber is the console's.** It marks focus, what is shown (the chosen tab, the shown space), your
   own action (Send, the primary button, the +), links, the cursor, and work: as on the desktop,
   *working* is amber. The other states have their own colours.
4. **Module headings in capitals.** Sections, sheets, top-level titles, the nav's labels, the
   drawer's buses and slots are Geist Mono in capitals, tracked; a section heading runs a 1dp rule
   to the edge. What is read at length (prose, row titles, body) stays in sentence case in Geist.
5. **One status, shown once.** A task or an agent says its state once: a square LED with its word,
   or the section it sits in, or its button (Accept), never two of them. Colour is never alone.
6. **No module inside a module.** A card holds rows and sections, never another card.

## Layout (Shepherdr 3.1's phone)

- **Bottom bar**: **Chat** (the shown space), **Tasks** (owns the Needs you count, the only badge),
  **Artifacts** (the shown space's; where its machine has no Shepherdr, the machine's herdr.doc docs). Each a glyph over its name in mono capitals; the chosen one amber,
  with a 2dp amber bar along its top edge.
- **Drawer of spaces** (`ui/spaces/Spaces.kt`), the desktop workbench's sidebar: the brand row (the
  HERDR chip, *HERDR*, an amber-framed *WORKBENCH* tag, search), *SPACES*, then each machine as a
  numbered bus (`BUS 1 · DEVBOX`, its meter: thin bars for CPU, memory and its tightest plan, amber
  near a limit and red past it; its connection LED; why, when it doesn't answer; a tap opens the
  machine) with its spaces as numbered slots (`SlotNumber`, the name in sentence case, the program's chip
  only where an agent really is, its one LED; the shown one on `selected` with an amber bar at its start and its
  number on amber). Classic herdr's and herdr-core's workspaces of one name are one slot. At its
  foot, *Settings* and *Add machine*. The circuit board shows, quietly, below the last bus.
- **Chat tab**: the shown space's Assistant (Shepherdr), else its agent (the one that needs you
  first), else its terminals. The bar's leading button opens the drawer; its layers glyph opens the
  space's menu: its tabs (each agent's chat and terminal, each shell), *New task*, *Assistant*,
  *Start an agent*, and its machine.
- **Tasks tab** (3.1's Tasks list): a *Filter tasks* field and a filter row (All, then each group
  with its count), then *Pinned*, *Needs you* (one row each, its answer beside it), *Ready* (with Accept), *Working*, *Queued*,
  *To do*, *Done* (five, then *Show N more*), *Idle*. One row per task: a task's agent is
  its row. A + offers *New task*, *Assistant* and *Start an agent*.
- **Wide** (840dp and more): the drawer stays open on the left as a sidebar; the app fills the rest.

## Colour

Dark is the workbench theme value for value (`ContrastTest.darkIsTheWorkbenchTheme`); light is its
counterpart on paper, with dark-amber frames, made in the same language.

| Token (`Herdr.colors`) | Workbench | Dark | Light | Use |
| --- | --- | --- | --- | --- |
| `ground` | `background` | `#0D0F10` | `#F1EEE6` | Screens, the chat canvas, the terminal |
| `panel` | `chrome` | `#0A0D0C` | `#ECE8DE` | Bars, the nav bar, the drawer, sheets, dialogs, toasts |
| `card` | `surface` | `#1B1F21` | `#FBFAF6` | Your chat turns, tiles, tracks |
| `hover` | `hover` (a step darker) | `#252B2E` | `#E4E0D4` | Pressed rows, keys |
| `selected` | `selected` | `#1E3A40` | `#D9E6E3` | The shown space, the chosen action |
| `input` | `input` | `#090A0B` | `#FFFFFF` | Text fields |
| `line` | | `#283033` | `#D5CFC0` | Rules between rows, a section heading's rule |
| `lineStrong` | `border` | `#4E5B57` | `#8E897C` | Module frames, keycaps, an off switch |
| `frame` | amber at half | `#7A5809` | `#B98A3A` | A quiet amber frame |
| `text` | `foreground` | `#DFE4E1` | `#1E2224` | Body and row text |
| `strong` | | `#F4F6F5` | `#0B0D0E` | Titles, emphasis |
| `muted` | `muted` | `#8FA3A8` | `#4F5B60` | Secondary lines, icons, headings, meta |
| `off` | `dim` | `#66727A` | `#9AA2A3` | Disabled, a resting LED, chip pins (not text) |
| `accent` | `accent` | `#FFB000` | `#7A4C00` | Amber that reads as text and frames focus |
| `primary` / `onPrimary` | `accent` / `accent_foreground` | `#FFB000` / `#0B0D0E` | `#FFB000` / `#0B0D0E` | The fill of your own action, with ink |
| `teal` | `ansi6` | `#4FD1C5` | `#0E7A70` | The runtimes' chip ink |
| `notice` | `ansi11` | `#FFCC4D` | `#6E5600` | Offline, stale, near a limit |
| `scrim` | | `#050606` 60% | `#281F16` 35% | Under sheets, dialogs, the drawer |

### States

| State | Workbench | Dark | Light | Agent | Task |
| --- | --- | --- | --- | --- | --- |
| Working | `working` | `#FFB000` | `#7A4C00` | working | working, merging |
| Blocked | `blocked` | `#FF5A4A` | `#A3211A` | needs you | needs you |
| Done | `done` | `#5FD38A` | `#17623A` | done | ready, done |
| Idle | `idle` | `#8FA3A8` (as text) | `#4F5B60` | idle, other | queued, to do |

A state shows as an `Led` (a 6–7dp square) with its word (`StatusBadge`: the LED and the word in
mono capitals in the state's colour, flat), or as the section it sits in. Red is also the colour of
destructive actions. Code takes its own syntax colours (`HerdrColors.syntax(role)`); the terminal
the workbench's sixteen ANSI colours, any cell held at 4.5:1 on its background.

## Type

**Geist Mono** (`Mono`) is the console's voice: titles, headings, labels, buttons, chips, the nav,
code, paths, keys and the terminal. **Geist** (`Sans`) is for reading: prose, row titles, body.
Both bundled (`res/font`, Regular, Medium, SemiBold). In sp, so they follow the phone's font size:

| Style | Size / line | Face | Use |
| --- | --- | --- | --- |
| `headlineMedium` | 18 / 23.4, +0.04em | Mono SemiBold, capitals | Top-level titles (TASKS) |
| `headlineSmall` | 18 / 23.4 | Sans SemiBold | A task's title, Markdown h1 |
| `titleLarge` | 15 / 20.3 | Mono SemiBold | Detail bars, sheets, dialogs, empty states (capitals) |
| `titleMedium` | 14 / 21 | Sans Medium | Row and card titles |
| `titleSmall` | 12 / 16.8, +0.08em | Mono SemiBold, capitals | Section headings, tiles |
| `bodyLarge` | 15 / 24 | Sans | Chat prose, your turns, menu rows |
| `bodyMedium` | 14 / 21 | Sans | Body and dialog copy |
| `bodySmall` | 12 / 18 | Sans | Subtitles, hints, meta |
| `labelLarge` | 13 / 18.2 | Mono Medium | Buttons, chips, segments, slots |
| `labelMedium` | 12 / 16.2 | Mono Medium | Counts, pills |
| `labelSmall` | 11 / 14.3, +0.08em | Mono SemiBold, capitals | Badges, field labels, nav labels, buses |
| `CodeStyle` | 13 / 20 | Mono | Code, the terminal (its own size) |

Capitals are written with `caps()`; what a screen reader hears keeps the words as written.

## Spacing, size and frames

`Space`: a 4dp grid, `xs` 4, `s` 8, `m` 12, `l` 16, `xl` 20, `xxl` 24, `xxxl` 32. Screen margin 16,
module padding 12. `Radius`: every one is 0. `Frame`: 1dp. Targets at least 48dp; bars 56dp; rows
48–56dp; icons 20dp; chip icons 20–22dp; LEDs 6–7dp.

## Icons

- **Dot-matrix glyphs** (`GlyphArt`, the desktop's px-chrome `gw` set) wherever the workbench draws
  one: search, settings, plus, minus, X, check, chevrons, server, monitor, terminal, folder, files,
  branch, pull request, compare, pin, archive, clock, layers, the sidebar. `HerdrIcons` returns them
  under Lucide's names, so screens don't change.
- **Lucide** (`HerdrIcons`) for the rest, at its own geometry (24-unit grid, 2-unit strokes).
  `IconsTest` keeps every icon one or the other.
- **Chip-package icons** (`ChipIcon`, `chipFor(program)`, `AgentMark`) for programs and agents, as the
  desktop marks them: Claude, Codex, a generic agent chip for the other agents; shell, git, vim, nvim,
  make, htop, ssh in the tools' grey; bun, node, npm, python, go, cargo, docker in the runtimes'
  teal; agents in amber. The body is a step off the ground, the pins `off`. No brand logos.

## The circuit board

`CircuitBackdrop` draws the desktop's `backgrounds/workbench.svg` covering its box, in colours a
step off the ground. It shows only where there is room to spare: behind empty states and under the
drawer's last bus. Never under rows of text; text over an empty state sits on the ground.

## Surfaces

| Surface | Fill | Edge |
| --- | --- | --- |
| Screen | `ground` | |
| Top bar, nav bar, bottom action bar | `panel` | a `lineStrong` rule on the content side |
| Module (`QuietCard`) | none (the ground) | `lineStrong` frame; chosen: `selected` with an amber frame |
| Sheet (`HerdrSheet`, `HerdrMenu`) | `panel` | an amber top edge, a short `lineStrong` handle |
| Dialog (`HerdrDialog`) | `panel` | amber frame |
| Drawer | `panel` | `lineStrong` rule at its edge |
| Toast | `panel` | amber frame (red for an error) |
| Composer, prompt card | `card` | `lineStrong`; the prompt card red, amber while typing |
| + (`HerdrFab`) | `primary` | square |
| Code block, terminal | `panel` / `ground` | |

## Components

| Component | Anatomy |
| --- | --- |
| `HerdrTopBar` | `panel`, a strong rule under it. Top level: the drawer's button, the title in mono capitals, actions. Detail: back arrow, title in mono, the place under it dim. A screen shown as a tab (the Chat tab) opens the drawer instead of going back, and the tab adds its own action after the screen's (`LocalBarDrawer`, `LocalInTab`, `LocalBarMore`). |
| `HerdrNavBar` / `NavItem` | See Layout. |
| `SectionHeading` | Mono capitals in `muted` (or a state's colour when it needs you), then a 1dp `line` rule to the edge or to its trailing action. |
| `QuietCard` | A module: a `lineStrong` frame, flat. Holds rows and sections. |
| Needs you rows (`WaitingRow`) | One row per item: the agent's chip, the title, its key and space, the question in two lines, and its one action beside it (Accept, Give it, Answer; a yes/no keeps both answers as square buttons, the main one amber). No status badge: the section says it. The full card stays in the task's chat. |
| `ListRow`, `ActionRow`, `MenuRow` | Optional leading cell, title, a dim line, trailing or chevron. A chosen action row sits on `selected` with a 3dp amber bar at its start. |
| `PrimaryButton` / `SecondaryButton` / `QuietButton` / `DangerButton` | Square, 48dp, mono labels: amber with ink / framed on the ground / bare / the danger tint framed in red. |
| `HerdrChip` | A framed cell; chosen: amber frame, amber text on amber's tint. |
| `SegmentedPills` | One framed track; the chosen segment amber with ink. |
| `ViewTabs` | The views of one thing (a task's Chat, Files, Changes, Pull request, Browser) under its bar: on `panel` over a `lineStrong` rule, names in mono capitals, the shown one amber with a 2dp amber bar along its bottom edge; scrolls sideways when they don't fit. |
| `HerdrField` | Label in mono capitals, the box on `input` framed in `lineStrong`, amber while focused. |
| `HerdrSwitch`, `SwitchRow` | Square: a framed track with a square knob; on, amber with an ink knob. |
| `StatusBadge`, `StatusLabel`, `Led` | See States. |
| `SlotNumber` | The desktop's space index cell: "01" in mono, framed; amber with ink when shown. |
| `IconTile` | A square framed tile; a status LED cut into its corner. |
| `ChatComposer`, chat | As before in behaviour (one round of controls, Send, Stop, mic, attach, held messages, the prompt card docked over it); square and flat, framed in `lineStrong`, amber while typing. Placeholder: "Message <agent>" everywhere, "Message the Assistant" for an Assistant. A turn at work shows one working line. |
| Prompt card | Framed in red (the chat's status line says it needs you); its action tag on the right; its choices as square buttons. |
| Terminal | `ground`, `text`, amber cursor, the workbench's ANSI colours; keys framed, an armed modifier amber. |

## States

- **Empty** (`EmptyState`): over the circuit board, a square framed tile with a dim glyph, the title
  in mono capitals, one dim sentence, the button that fixes it when there is one.
- **Loading** (`LoadingState`, `Spinner`): a thin dim spinner with square ends, a dim line. Before
  any machine answers, the Tasks tab says *Connecting to N machines…*, not an error.
- **Error**: `ErrorMessage` (danger tint, red frame, one sentence, Retry); `ErrorState` full-area.
- **Offline**: a `notice` "Offline · showing the last known state" over the stale content.

## Motion

Short and quiet: navigation fades and slides (200ms); sheets rise; dialogs fade (150ms); toasts drop
in from the top; the nav's amber bar cross-fades (180ms). A working agent's dot breathes; nothing
else moves on its own.

## Accessibility

- Every target is at least 48dp (terminal keys 52×48).
- Text 4.5:1 and marks 3:1 in both themes, on every surface text sits on and on the state tints
  (`ContrastTest`); the amber focus frame 3:1 on the ground.
- Layouts hold at 200% font scale; all type is in sp.
- Words in capitals are drawn so; screen readers get them as written (`contentDescription`).
- Icon-only buttons have content descriptions; rows that open something say what; headings are
  headings; states are words, not only colours.
- Nothing is only behind a long press: a card or row with a long-press sheet offers its actions as
  TalkBack custom actions on itself.
- Back is predictive (`BackHandler`s, never `onBackPressed`).
- Chats and the terminal have their own text sizes (Settings → **Text size**).
