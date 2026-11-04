# Feature audit (HERD-10)

What to drop, what to fix and what to add, so the app reads as one tidy phone client for what the owner
actually runs now: **Shepherdr 3.1** (its phone web layout: `/data/jpl/shepherdr-3.1/docs/web.md`,
`docs/parity.md`, `docs/parity/*.md`, `lib/features.json`) and the **Herdr desktop** with herdr-core
(`/data/jpl/Work/personal-herdr-desktop`). Made on 2026-11-04 from `main` at `c966099`, by reading `app/`
(`AppNav.kt`, `Panes.kt`, `MainActivity.kt`, `ui/**`) and `core/`, and by rendering the preview scenes
(`./gradlew -p tools/ui-preview render`, `tools/ui-preview/build/shots/`). Scene names below are the
`Scene("…")` names in `tools/ui-preview/src/main/kotlin/preview/Scenes.kt`.

**In flight, not repeated here.** HERD-8 moves the app to a Shepherdr 3.x client and deletes the 2.1 paths
(`ui/board/WorkBoardScreen.kt`, `WorkTaskScreen.kt`, `TaskChatScreen.kt`, `ui/coordinator/SpaceCoordinator.kt`,
`core/shepherdr/*`, port 7787). HERD-9 follows with the workbench look, flat style, one status per thing, no
nested cards and the 3.1 phone layout. Items below marked **HERD-9** fit that cleanup (removals and small
fixes); items marked **Own task** change behaviour or need new data and should be tasks of their own.

What the owner's machine runs today (read on this machine): herdr-core and the desktop app (`herdr-app`), the
Shepherdr 3.1 service, and still a classic `herdr server` holding one idle workspace; herdr-projects is
installed, but its data folder was last written on 2026-11-01.

## How the app is laid out now

- Bottom bar (`AppNav.kt` `AppNavBar`): **Agents**, **Board**, **Machines**. Settings sits behind a gear in
  the Machines bar (`ui/machines/MachinesScreen.kt:64`); the code browser behind `<>` beside it.
- Agents (`ui/agents/AgentsScreen.kt`): Needs you cards, then Pinned / Working / Done / idle rows; bar: usage
  chip, search, brief, bell; an **Ask** button that opens a Coordinator (long press: Start work).
- Board (`ui/board/BoardsScreen.kt`): every machine's board, for two board kinds at once: herdr-projects 1.x
  (`BoardScreen.kt`, `ReviewScreen.kt`, `ui/routines/*`) and Shepherdr 2.1 (`WorkBoardScreen.kt`).
- A machine (`ui/machines/MachineScreen.kt`): tiles (Board, Coordinator, Health), banners (Usage, Agent
  logins, Routines), classic herdr's workspaces, then herdr-core's spaces; a 13-row ⋯ menu.
- 3.1's phone layout instead: a drawer of spaces grouped by machine (Pinned, sections, a meter per machine,
  Settings at its foot), a bottom bar of **Chat**, **Tasks**, **Artifacts**, a header ⋯ with the space's tabs
  and the + menu, and a task view with Chat, Terminal, Files, Changes, Pull request and Browser tabs.

## 1. Remove

Ranked by how much the removal helps the person using the app.

| # | What | Why | Files | Who |
|---|---|---|---|---|
| R1 | **The herdr-projects 1.x work board**: its board, review cards, Routines, its Coordinator and the v1 half of the Board tab | Shepherdr 3.1's task list replaces it, and carrying two board models doubles every list that shows work: Needs you, the Board tab, search, the brief, the status widget and where a notification opens. herdr-projects was last written on 2026-11-01 on the owner's machine. | `ui/board/BoardScreen.kt`, `BoardSheets.kt`, `BoardRow.kt`, `ReviewScreen.kt`, `StartAgent.kt`, the v1 parts of `Boards.kt` and `BoardsScreen.kt`; `ui/coordinator/CoordinatorScreen.kt`, `BoardBits.kt`; `ui/routines/*`; `core/board/Board.kt`, `Tasks.kt`, `Approvals.kt`, `Automations.kt`, the herdr-projects half of `WorkBoard.kt`; scenes `board*`, `task*` (v1), `review*`, `routine*`, `coordinator`, `coordinator-ask/-needs/-task/-up/-new-message` | **HERD-9** (its brief's "old board era" removal; the largest item, split it out if HERD-9's diff gets too big) |
| R2 | **Three ways into "a Coordinator"** (`AskScreen` chooser, herdr-projects' Coordinator, 2.1's space Coordinator) | After R1 and HERD-8 only 3.1's per-space **Assistant** is left. The Ask button, quick-settings tile, Ask widget, bubbles and launcher shortcut should open the space's Assistant chat directly, as 3.1's + menu does. | `ui/share/AskScreen.kt` (keep only the share path), `share/CoordinatorTile.kt`, `share/Widgets.kt`, `share/Shortcuts.kt`, `bubble/Bubbles.kt`, `Panes.kt` `openAgent` | **HERD-9** |
| R3 | **Health, Usage, Agent logins and Routines twice on a machine** (tile or banner, and again as ⋯ menu rows) | The same place reached two ways on one screen; the menu is 13 rows long because of it (scene `machine`). | `ui/machines/MachineScreen.kt:181-203`, `ui/machines/Places.kt` | **HERD-9** |
| R4 | **Classic herdr's admin from the phone**: Plugins, Integrations, Server and updates, herdr's saved machines, Notify herdr…, Import herdr machines | Server administration is rarely done from a phone, and none of it exists on herdr-core machines (`docs/HERDR_CORE.md` "No match"). | `ui/admin/AdminScreen.kt`, `AdminModel.kt`, `ui/machines/MachineImport.kt`, the rows at `MachineScreen.kt:181-196`; route `Routes.ADMIN` | **HERD-9** |
| R5 | **The machine screen's Refresh button** | The screen already follows the live events stream (README "Managing from the phone"); a manual refresh suggests it doesn't. | `ui/machines/MachineScreen.kt:178` | **HERD-9** |
| R6 | **"Keep screen on" twice in the terminal** (a sun in the bar and a row in ⋯) | One control, two places. Keep the menu row. | `ui/terminal/TerminalScreen.kt:442`, `:462` | **HERD-9** |
| R7 | **The brief as a top-level button** (newspaper on Agents) | "What happened while away" repeats Needs you, the Done list and the bell, and 3.1 carries the same news in each task's chat. Keep the scheduled brief in Settings for whoever turns it on; drop the bar button. | `ui/agents/AgentsScreen.kt:333`, `ui/brief/BriefScreen.kt` | **HERD-9** (button); deleting the brief entirely (`brief/*`, `ui/brief/*`, `core/brief/*`) is a separate call |
| R8 | **Classic herdr's workspace tools**: Layout, Resize pane, Desktop size, New workspace/tab/agent sheet, Worktrees, Quick commands, Dev servers | They work only on a classic `herdr server` (each is hidden on herdr-core: `MachineScreen.kt:177`, `:209`; `TerminalScreen.kt:446`, `:455`, `:465`). The owner's agents run in herdr-core, and the classic server here holds one idle workspace. Retire them together with the classic transport once the owner confirms no machine needs it. | `ui/layout/LayoutScreen.kt`, `ui/machines/NewSheet.kt`, `ui/worktrees/*`, `ui/quick/*`, `ui/preview/*`, `host/herdr-quick/`, `core/quick/*` | **Own task** ("Retire classic herdr"), after HERD-8 |
| R9 | **The machine-wide code browser entry** (`<>` on Machines → machines → repos) | 3.1 shows Files and Changes per task, which is where a phone user looks. Keep the code screens (`ui/code/*`), but reach them from a task (A3) and drop the machine-wide entry. | `ui/machines/MachinesScreen.kt:62`, `ui/code/CodeHome.kt` | **Own task**, together with A3 |

Not counted: Shepherdr 2.1's screens and client (HERD-8).

## 2. Fix (sloppy spots)

Ranked by how often someone hits it. Shots: `tools/ui-preview/build/shots/<scene>.png`.

| # | Screen (scene) | What's wrong | Fix | Who |
|---|---|---|---|---|
| F1 | Bottom bar (`agents`, `machines`) | **Agents** and **Board** carry the same red count (both `badge = needsYou`, `AppNav.kt:456-457`), so four needs read as eight, and the Board tab's subtitle points back to the other tab ("2 more on Agents"). | One tab owns Needs you, as 3.1's **Tasks** does; badge that tab only. | **HERD-9** |
| F2 | Agents (`agents`, `agents-v2`) | Status said twice: a red **needs you** pill on every card under the red heading "Needs you · 4" (`ui/agents/NeedsYou.kt:221`), and a green **ready** pill on a card whose button already says Merge. | Drop the pill inside the section; the section says it. | **HERD-9** |
| F3 | Agents (`agents-v2`, `notifications`) | **One task shows twice.** In `agents-v2`, AND-21 "Show when each machine last answered" is a Needs you card and again a Working row. In `notifications`, API-3 "Speed up /jobs" is both an agent question card and a task card: the app ties an agent to its task by the names herdr-projects gives them, and when that tie fails the two show apart. | One row per task, keyed by the task's id; 3.x knows each task's agent, so use that link (HERD-8's data). | **HERD-9** once HERD-8 gives task↔agent |
| F4 | App frame | Navigation doesn't match the 3.1 phone layout the owner uses daily: Agents / Board / Machines here, Chat / Tasks / Artifacts plus a spaces drawer there. Needs you lives on two tabs (F1). | Follow 3.1: drawer of spaces by machine, bottom bar Chat · Tasks · Artifacts, header ⋯ for tabs and +. | **HERD-9** (its brief, point 5) |
| F5 | Machine (`machine`, `machine-shepherdr21`) | **The same project twice** under two jargon headings: classic herdr's "Workspaces · 3" (herdr-android, api-server, website) and "herdr-core · 2 / herdr-core 0.1.14, sessions shepherdr: their spaces, tabs and terminals" (herdr-android, website again). Nobody can tell which is which. | Merge by space name with one heading ("Spaces"); show the backend only in a detail line, never as a heading. | **HERD-9** (the headings are UI; merging the two lists touches `HostSession.kt` `merge`) |
| F6 | Machine (`machine`) | Six blocks before the first workspace: three tiles, then Usage, Agent logins and Routines as full-width banners framed in amber or red. The workspaces start halfway down the screen. | One compact row of places; a banner only when something is wrong (out of limit, login expired). | **HERD-9** |
| F7 | Machine ⋯ menu (code `MachineScreen.kt:181-203`) | 13 rows mixing server admin, imports, code, quick runs, worktrees and places already on screen. | After R3, R4: Edit machine, Search, and what is left. | **HERD-9** |
| F8 | Machines (`machines`) | Each machine says Online twice (green dot and a green "Online" pill) and its counts as four coloured chips; Settings is only reachable from this tab's gear. | One status mark; counts as one line; Settings at the drawer's foot, as 3.1's sidebar has it. | **HERD-9** |
| F9 | Agents, Ask button (`agents`) | **Start work is a hidden long press** on Ask (`AgentsScreen.kt:349`); the button also covers the last rows while scrolling. | A visible + with 3.1's menu: New task, Assistant, Start an agent. | **HERD-9** |
| F10 | Agent's long-press sheet (`agent-actions`) | Nine rows, four of them overlapping alert controls: Notify me when it finishes, Pin, Snooze, Mute. | 3.1's model: Pin, and one **Notify** choice per space (All / Needs me / Off); a watch becomes "Notify: All". | **Own task** (changes the triage and push stores, `core/push/Rules.kt`) |
| F11 | Agents bar (`agents`) | Four controls in the bar (usage chip, search, brief, bell); the bell's list repeats what Needs you and Done already show (`notifications`). | Search only; usage to the drawer's machine meter; brief per R7; notices fold into Needs you / Done. | **HERD-9** |
| F12 | Agents, first launch | While machines are still connecting the screen says "Not connected yet · Check the Machines tab for connection details" (`AgentsScreen.kt` empty states), which reads as an error. | A loading state: "Connecting to 3 machines…", the error only once one fails. | **HERD-9** |
| F13 | Agent chat on herdr-core (`chat-core-working`) | herdr-core reports no transcript, so an hc agent's chat is its **screen** under "No transcript for this agent, so this is its screen" (`docs/HERDR_CORE.md` "Chats"), while a classic agent's chat is the folded transcript. All of the owner's agents run on herdr-core. | Read the agent's own transcript from its `cwd` (the follow-up `HERDR_CORE.md` names), or 3.1's chat for task agents. | **Own task** (M, core) |
| F14 | Terminal on herdr-core | No Find and no scrollback (`TerminalScreen.kt:443`, `:446`, `HERDR_CORE.md` "Scrolling back"). | Fill history from `terminal.read`; enable Find on it. | **Own task** (M) |
| F15 | Machine (`machine-shepherdr21`) | A space's red dot doesn't match the agent row under it ("claude · done"): the dot is the space's worst tab, the row is the tab shown. | Show the status on the tab chip that has it, or say which tab needs you. | **HERD-9** |
| F16 | Task chat (`task-shepherdr21`) | "Working" said three times on one screen: a **working** pill under the title, a "Working · 4 steps" fold and a "Claude is working…" line over the box. | One working line, as 3.1's chat has (its `core/chat-view.js` working line). | **HERD-9** |
| F17 | Composer (`chat-core-working`, `task-shepherdr21`) | The box's placeholder changes by screen: "Message codex…" in an agent chat, "Tell claude…" in a task chat. | 3.1's "Message <agent>" everywhere. | **HERD-9** |

## 3. Add

Ranked by value to a phone user of 3.1. Size: S (a day or less), M (a few days), L (a week or more).

| # | What | Value | Size | Depends on | Who |
|---|---|---|---|---|---|
| A1 | **3.1 task alerts on the phone**: "HERD-4 · Needs you", "HERD-4 · Finished" opening its Accept card, with Accept and answer buttons on the notification | The phone's main job. Today pushes come from `host/herdr-push`, which knows agents but nothing of Shepherdr's tasks (no mention of Shepherdr in it); 3.1 sends these alerts only to web pages that subscribed (`docs/web.md` "Push alerts to phones"). | M | HERD-8; host side: herdr-push reads 3.x task changes, or the 3.1 service posts to the app's UnifiedPush address | **Own task** |
| A2 | **Task view tabs: Files, Changes, Pull request** (with its checks), as 3.1's task view | Checking an agent's work before Accept, on the phone. The app has the pieces (`ui/code/CheckoutScreen.kt` Files/Changes/History, `DiffScreen.kt`, code notes to the agent) but only from a machine-wide browser. | M | HERD-8 (the task's folder and PR) | **Own task**, with R9 |
| A3 | **Dictation into the composer** | Voice messages today send audio only ("the phone turns nothing into text"); 3.1 dictates through the service (whisper.cpp), and Android has an on-device recognizer too. Typing long replies on a phone is the biggest friction in chats. | S (Android recognizer) / M (3.1's dictation) | none / HERD-8 | **Own task** |
| A4 | **Spaces drawer** with machines, Pinned, sections, unread spaces in bold and a per-machine meter (CPU, memory, plan usage), Settings at its foot | 3.1's main way around; also replaces the Machines tab, the usage chip and the Health/Usage banners. | M | HERD-8 (spaces), F4 | **HERD-9** (layout); the Notify setting is F10's task |
| A5 | **Tasks list as 3.1's**: filter row (Needs you, Working, Ready, More), Group by (Status / Space / Machine), Archived with search, Automations group | Finding a task across machines in two taps. | S–M | HERD-8 | **HERD-9** (layout); Automations rows need HERD-8's data |
| A6 | **Secret asks** ("Wants the secret NAME") answered from the phone in a masked box | An agent waiting on a key is blocked until someone is at the desk; 3.1's web lists it under Needs you. | M | HERD-8; a security review of how the value travels | **Own task** |
| A7 | **Autopilot switch** per space and Settings › Autopilot | Turning a space's autopilot on or off away from the desk (3.1 header switch, `docs/parity/autopilot.md`). | S | HERD-8 | **Own task** |
| A8 | **Tab menu**: Switch agent, Restart, Rename, Close; Model menu | Recovering a stuck agent from the phone (3.1 `docs/parity/header.md`). | S–M | HERD-8 | **Own task** |
| A9 | **Chat parity**: reply/quote a message, reactions, find in chat, task news lines | Smaller conveniences 3.1's chat has (`docs/parity/chat.md`). | S each | HERD-8 | **Own task** |
| A10 | **Needs you widget with buttons** (rows with Accept / Yes / No), beside today's count and Ask widgets | Answering without opening the app. | M | A1 | **Own task** |
| A11 | **Pull request review from the phone**: threads, comment, approve, request changes, checks (the desktop's PR and Reviews panels) | Reviewing on the go; the desktop has it, 3.1 shows only the PR's state. | L | A2 | **Own task**, later |
| A12 | **Live browser** of a task (3.1's Browser tab) | Seeing what the agent sees in its browser. | L | HERD-8; the 3.1 service's relay | **Own task**, later |
| A13 | **Docs viewer** for `herdr.doc/2` (the desktop's Docs panel: investigations, runbooks) | Reading an agent's write-up on the phone. | M | none (files over SSH) | **Own task**, later |

## For HERD-9, in short

Removals R1–R7 (R1 is the big one), fixes F1–F9, F11, F12, F15–F17 and the layout parts of A4 and A5. F3 waits for
HERD-8's task↔agent link. Everything else is its own task.
