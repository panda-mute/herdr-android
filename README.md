# <img src="docs/screenshots/icon.webp" alt="" width="48" align="center"> Herdr for Android

A native Android client for [herdr](https://herdr.dev) hosts: see your agents across machines, answer the blocked ones, and open any pane as a live terminal. Kotlin and Jetpack Compose. It wears the [herdr web UI](https://github.com/devswha/herdr-web-ui)'s look ("a warm terminal": amber on lamp-lit graphite or ledger paper, Lucide icons, the agents' own marks), carried over from its design tokens (MIT) into the app's own design system ([`docs/DESIGN.md`](docs/DESIGN.md)).

<table>
  <tr>
    <td align="center" width="25%"><img src="docs/screenshots/agents.webp" alt="Agents list" width="200"><br><sub><b>Agents</b>: what needs you on every machine first, answerable on the card</sub></td>
    <td align="center" width="25%"><img src="docs/screenshots/chat.webp" alt="An agent's chat" width="200"><br><sub><b>Agent chat</b>: the agent's own transcript, each turn's work folded to one line</sub></td>
    <td align="center" width="25%"><img src="docs/screenshots/blocked.webp" alt="A blocked agent asking for approval" width="200"><br><sub><b>Needs you</b>: the question docked over the composer, answered with a thumb</sub></td>
    <td align="center" width="25%"><img src="docs/screenshots/coordinator.webp" alt="Coordinator chat" width="200"><br><sub><b>Coordinator</b>: talk to the work board</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/machines.webp" alt="Machines list" width="200"><br><sub><b>Machines</b>: status per host, also through a jump host</sub></td>
    <td align="center"><img src="docs/screenshots/machine.webp" alt="A machine's workspaces" width="200"><br><sub><b>A machine</b>: its workspaces, tabs and panes</sub></td>
    <td align="center"><img src="docs/screenshots/terminal.webp" alt="Terminal" width="200"><br><sub><b>Terminal</b>: any pane, live, sized to the phone</sub></td>
    <td align="center"><img src="docs/screenshots/settings.webp" alt="Settings" width="200"><br><sub><b>Settings</b>: the SSH key, with backup and restore</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/board.webp" alt="The work board" width="200"><br><sub><b>Board</b>: tasks in the board's colours, the ones that need you first</sub></td>
    <td align="center"><img src="docs/screenshots/task.webp" alt="A task's details" width="200"><br><sub><b>A task</b>: next step, reviews, links, one tap to approve</sub></td>
    <td align="center"><img src="docs/screenshots/agents-light.webp" alt="Agents in light mode" width="200"><br><sub><b>Light mode</b>: follows the phone</sub></td>
    <td align="center"><img src="docs/screenshots/board-light.webp" alt="The board in light mode" width="200"><br><sub><b>Board, light</b></sub></td>
  </tr>
</table>

The screenshots come from a demo host with made-up projects (see [`docs/screenshots/demo-host`](docs/screenshots/demo-host)). The look (colours, type, icons, spacing, components) is described in [`docs/DESIGN.md`](docs/DESIGN.md); the design tokens are the herdr web UI's (MIT, by devswha), and the fonts, icons and marks are credited in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Getting the app

On your phone, open the repo's [latest release](https://github.com/makefunstuff/herdr-android/releases/latest) and tap **herdr-android.apk** (the direct link is `https://github.com/makefunstuff/herdr-android/releases/latest/download/herdr-android.apk`). Android asks you once to allow installs from your browser or file manager.

To update, do the same again: every build is signed with the same key and has a higher version code, so it installs over the app you have and keeps its machines and SSH key. No uninstall needed.

Only the default branch publishes a release (tagged `v0.1.<run number>`). Other branches and pull requests build the APK too, as the `herdr-android-<run number>` artifact on their Actions run page, without a release.

## Keeping the SSH key

The key stays through updates. An uninstall or a new phone wipes it, so back it up first: **Settings → SSH key → Back up** saves it as an OpenSSH private key protected by a passphrase you choose (`ssh-keygen -y -f herdr-android-key` reads it too). After a reinstall the app offers **Restore or import** before it makes a new key; the same button is in Settings. It also takes an existing OpenSSH (or PEM) private key, with or without a passphrase.

The app never replaces a key on its own: it only makes one when it has none and you ask for it, and **New key** asks before throwing the old one away.

## Signing key (once, for the repo owner)

Releases must all be signed with one key, or Android refuses the update. Create it once and keep a copy somewhere safe; losing it means everyone has to uninstall (and restore their SSH key backup) once.

```sh
keytool -genkeypair -keystore herdr-release.jks -alias herdr \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Herdr for Android"
base64 -w0 herdr-release.jks > herdr-release.jks.b64
```

Then add these secrets under **Settings → Secrets and variables → Actions** (or with `gh secret set NAME`):

| Secret | Value |
| --- | --- |
| `HERDR_KEYSTORE_BASE64` | contents of `herdr-release.jks.b64` (`gh secret set HERDR_KEYSTORE_BASE64 < herdr-release.jks.b64`) |
| `HERDR_KEYSTORE_PASSWORD` | the keystore password |
| `HERDR_KEY_ALIAS` | `herdr` |
| `HERDR_KEY_PASSWORD` | optional: the key password, only if it differs from the keystore password (keytool uses the same one by default) |

Without these secrets, and for local builds, the APK is signed with the normal debug key and the default branch skips publishing a release (and keeps the old `apk-*` pre-releases until the first real one). To sign a local build with the real key, set `HERDR_KEYSTORE_FILE`, `HERDR_KEYSTORE_PASSWORD` and `HERDR_KEY_ALIAS` and run `./gradlew :app:assembleRelease`.

## Setting up a machine

**By its code**, with nothing to copy or type:

1. On the machine running herdr, run `herdr-push pair`. A machine the app already set up for notifications has it in `~/.local/share/herdr-push/`; elsewhere (or where that one is older and doesn't know `pair`), fetch it:
   ```sh
   curl -fsSLO https://raw.githubusercontent.com/makefunstuff/herdr-android/HEAD/host/herdr-push/herdr-push
   python3 herdr-push pair
   ```
   It shows a QR code with where the machine is (its Tailscale name and address, its address on the local network), its user, its SSH host key's fingerprint and a one-time code, valid five minutes.
2. In **Machines**, tap **Add machine**, then **Scan the code**. The phone's own code scanner reads it (Google Play services': the app asks for no camera permission). Without one, **Paste the link** takes the link printed under the code.
3. The app finds the machine at the first address that answers with that host key and shows what it found. **Pair and add** logs in with the one-time code, which can do nothing but hand over this phone's public key: it goes into `~/.ssh/authorized_keys` there, the code is spent, and the app pins the host key and opens the machine.

How it works and what it touches: [`host/herdr-push/README.md`](host/herdr-push/README.md#pairing-a-phone).

**By hand** (**Add machine → Type the address instead**):

1. Open **Settings** (the gear in the Machines bar) and copy the app's public key.
2. Add it to `~/.ssh/authorized_keys` on the machine running herdr.
3. In **Machines**, tap **Add machine**, then **Type the address instead**, and enter `user@host[:port]`. The app pins the host key on first connect.

Machines reachable only through another one can use it as a jump host ("Connect through"). For zero-touch or read-only machines, **Allow host scripts** (on by default) can be turned off so the app skips copying its helper scripts (`herdr-usage`, `herdr-logins`, `herdr-push`) there. From a machine's menu, **Import herdr machines** adds the machines its herdr already knows (`herdr machine list`).

## How it talks to herdr

The app opens an SSH connection and reaches herdr's socket API through `direct-streamlocal` (falling back to `herdr remote-api-bridge`). herdr answers one request per socket connection, so each call opens a fresh channel; `events.subscribe` keeps one channel open for live updates. Terminals use `herdr terminal session control`.

## Needs you, first

The **Agents** screen opens with one **Needs you** list across every machine: agents asking a question, and work board tasks waiting for you (ready to approve, review failed, plan ready, pick one, asks you, stopped, went wrong). An agent and the task it works on show once, as one card (the app ties them by the names herdr-projects gives its agents and workspaces). Each card carries its answers:

- a question: **Yes** and **No** (or the menu's two choices), and **Reply**. Like a notification's buttons, Yes and No read the agent's screen again first and press nothing if the question changed; a longer menu opens in the chat.
- a task: **Approve and merge**, **Fix** (the review's findings or the failure sent back to its agent), **Approve plan**, **Reply** or **Continue**, and **Open**: finished work opens on its review card (below), the rest on its board.

Below it, the agents at work are one line each, with a thin bar (their task's progress when the board knows it). Finished ones wait under **Done** until you open their chat on the phone, swipe them right, press **Mark all seen**, or look at their pane in herdr itself (herdr then says idle; the app reads its `completion_seq` to tell one finish from the next); a finish seen rests with the idle ones.

Swipe an agent left for **Pin** (it stays on top, under Needs you, in the order pinned), **Snooze** (off the list into **Snoozed** at the bottom, until it changes state or until a time: in an hour, in three, tomorrow at nine) and **Mute** (no notifications from it: no alerts, no Live Update while it works, no in-app alert; a watch you set on it still comes). The same are in its long-press sheet and in TalkBack's actions on the row. A question always shows in Needs you, whatever its agent's pin, snooze or mute. All of it is kept on the phone, and goes when the pane does. One line on top says how many machines are online and filters the list to one. **Ask** opens the Coordinator: the one used last, or it asks which machine. The tabs are **Agents**, **Board** and **Machines**, each badge counting what waits for you; Settings is the gear in the Machines bar. The boards are read every 15 seconds while the list is in front (every 10 on the Board tab).

## Search

The search icon on **Agents**, **Board** and a machine (or **Ctrl+K** with a hardware keyboard, from anywhere) opens one box for the agents, the boards' tasks, the machines, their workspaces and the words in the recent chats. Chips above the results filter them to what **Needs you**, what is **Working** and what is **Done**, each with its count. From a machine it searches only that machine, until its chip is taken off. Every word typed has to be in a result, and a word at the start of a name counts most; with nothing typed it lists the agents, the tasks waiting for you and **Start work…**.

Words in the chats are looked for on the machines themselves: each running agent's own transcript (Claude Code, codex, pi and omp) is searched where it is, a moment after you stop typing (three letters at the least), and what was said around the words shows with who said it and when; tool calls and their output don't count. Under the results, **New task about "…"** starts work about the words, and **Open a terminal in …** opens a shell in the workspace found (each workspace row has the same terminal button). Enter opens the first result, Esc goes back.

## Start work

**Start work** asks first what you want, then how and where. It opens from a long press on **Ask** (or the **Start work** button when no machine has the work board), from the search, and from the launcher's shortcut.

- **Board task**: the sentence becomes a task on the chosen machine's board (its first line the title, the rest the description), in one of the board's repos or a folder, with a worktree, checks and a review; the board picks its agent (shown, with what is left of its limit), at once or later.
- **Agent now**: an agent starts in the folder, in a new tab of the workspace that sits there (else a workspace of its own), named after the sentence's first words, and the sentence is its first message. The agents offered are the ones installed on that machine: those herdr's integrations find, and every other kind herdr can start whose program is on the machine's PATH (gemini, amp…).

The folders offered are the ones work was started in from the phone, then the workspaces' (a task's worktree counts as its repository); **Browse…** walks the machine's folders, Git repositories marked. The phone never starts an agent in a mode that skips its permission prompts: it asks before it acts, as it does at the desk. A machine's **New › Agent** sheet has the same first-message field, lists the agents installed there, and starts the agent in a new split beside the workspace's focused pane.

## Managing from the phone

A machine's screen opens with its places as tiles in one row, each with a live line: **Board** (how many tasks need you, else how many are at work), **Coordinator** (idle, working, waits for you) and **Health**; **Usage** is a line under them (a tile on a machine without the board). Its workspaces follow, each with its branch and pull request under its name and one row per pane: the agent and its state, or the shell and its folder, a tap opening its chat (or terminal) and the terminal icon its terminal. Tab chips and the layout thumbnail show only for a workspace with several tabs. The bar says when the machine last answered ("Online · answered 4 s ago"), and **New** steps aside while you scroll down.

Everything that exists in a session is editable from the app, not just readable. A workspace or tab header has an actions button (a workspace's one tab is under its actions, **Its tab**), and panes and agents answer to a long press: rename, focus, reorder, move, swap, zoom, clear a pane's scrollback, read what a pane is running, and ask `agent.explain` why an agent is in the state it shows. Closing a workspace, tab or pane asks first. herdr's own refusals ("There's no pane in that direction.") come back as plain sentences.

The screens follow herdr's `events.subscribe` stream, so a change made anywhere — desktop or phone — appears here. Notifications are the exception: herdr has no list of them, so the app derives them from agents finishing or blocking and shows them behind the bell in the top bar; the menu can also show a notification on the desktop running herdr.

## The terminal

Any pane opens as a live terminal, sized to the phone (**Desktop size** in its **⋯** keeps the host's size and pans).

- **The key bar** over the keyboard has two rows of keys on a phone: Esc, Tab, ^C, ^R (the shell's history), ^W (delete a word), `|`, `~`, **fn** (a page of F1 to F12, Page Up and Down), Ctrl, Alt, Space and the arrows. Ctrl, Alt and Shift are sticky: a tap holds one for the next key (the bar's or the keyboard's: Ctrl then ← is a word back), a second tap keeps it on (underlined) until a third. **Hold Space and drag** to move the cursor: a cell for each 12 dp across, a line for each 24 dp up or down.
- **Your own keys.** The pencil at the end of the bar (or **⋯ → Edit key bar…**) lists the bar's keys, each moved earlier or later or taken off, and offers more by kind: modifiers and pages (Shift, the keyboard key), shortcuts (^D, ^Z, ^L, ^A, ^E, ^U, ^K, ^B, Alt+B, Alt+F, Alt+., Shift+Tab), keys (Enter, Backspace, Delete, Home, End, Page Up and Down…) and characters; a tap puts a key on the bar or takes it off. **A shortcut of your own** builds any other: Ctrl, Alt and Shift with a letter, a sign or a key's name (`F5`, `Home`, `Left`). **Back to the first keys** undoes it all. The bar is the phone's, the same in every terminal, and stays through restarts.
- **Selected text** (a long press, or **⋯ → Select text**, then a drag) gets **Copy**, **Quote in chat** and **Send to Coordinator**. Quoting puts it in a fenced block, with where it came from (`From the terminal (shell on devbox):`), at the end of an agent's message box and opens its chat: the workspace's agent, or the one you pick when there are several. **Send to Coordinator** puts it in the machine's Coordinator chat the same way (on a machine with the board). Nothing is sent until you send it.
- **Keep screen on** (the sun in the bar, or the **⋯**): the screen stays on while a terminal is open, for a long build or a log you follow. It is kept for every terminal until turned off.

### Dev servers

A workspace's **Dev servers** (in its actions on the machine screen, or a terminal's **⋯**) opens what it serves in an in-app browser through the app's own SSH connection: nothing has to listen on the machine's network, and nothing is installed there.

- **What listens.** The app asks the machine what listens on its TCP ports (`ss -ltnp`, `lsof` on macOS) and which of those processes were started in that workspace, by the `HERDR_WORKSPACE_ID` they inherit from its panes (or, for one started outside herdr, by its folder), as the health probe ties processes to panes. Each shows as a chip with its port and what it is (`:5173 vite`, `:8000 uvicorn`, `:6006 storybook`); those that don't answer HTTP (a Gradle daemon) come last, dimmed, and the machine's other ports are a chip away (**+2 on the machine**). **Refresh** asks again.
- **Through SSH.** The page is the machine's port forwarded to one on the phone (`ssh -L` over the app's connection, a channel per request), at the same number when it is free there, so a page that names its own port (a dev server's live reload) still finds it. It is only on the phone's own loopback address, and goes when the screen closes. Links that leave the machine open in the phone's browser; **⋯ → Open in the phone's browser** opens the page there too, while the screen is open.
- **Phone size**, on at first, lays the page out at the phone's width, as a phone would see it; off, at a desktop's (1280 px, zoomed out).
- **Screenshot to agent** puts the page as it shows in an agent's message box (the workspace's agent, or the one you pick), with a line saying which page and size, and opens its chat: the picture goes as any photo does (below).

## Reading a chat

An agent's chat is its own transcript (Claude Code's, codex's, pi's or omp's), cut to fit a phone that shows about fifteen lines:

- **One line per turn's work**, as in the herdr web UI: everything the agent did between your message and its answer (the calls, its words on the way) folds under **Worked for 2m 36s · 1 edit · 2 file reads · 1 command** once the turn is over, and the answer stays out of the fold. Calls that failed are counted apart, in red (**· 1 failed**), since the fold hides their rows. A tap opens or folds it again, and it stays as you left it. The turn at work is open, headed **Working for 3m** (or **Needs you** while it asks); a turn whose last words came before its last call stays open, so nothing it said is hidden.
- **A time on each turn**: `10:05`, or `Oct 16, 09:12` on another day.
- **Short paths**: a file in the repo shows from it, deep ones as `core/…/host/HostState.kt`, and an edit says what it changed (`+12 −2`). A tap on a row still shows the whole call and what it printed.
- **One Working line**: the strip under the bar says what the newest turn is at (*Working · editing HostState.kt*) and the turn's own header how long it has worked (*Working for 3m 12s*), so there is no second "Working" at the bottom.
- **While you were away**: coming back to a chat after five minutes or more, a card says what happened since it was last on screen, in up to three lines from the transcript (what the agent answered, what it was asked and what it is at, the files it changed with `+61 −8`, the commands it ran and whether the tests passed). It goes when you write, or with **Hide**. "Last on screen" is kept per chat on the phone: when you left it, or put the app away while it was open.
- **"2 new messages"**: read further up while the agent writes, and a pill counts what came in; a tap goes to the newest (with nothing new, the round arrow does).

The times, kinds, failures and edits come from the same reading of the agent's session as the activity timeline (below); a transcript without times still folds its turns, without durations or times.

## Writing to an agent

The composer knows the agent it writes to:

- **Held messages**: what you send while the agent works isn't typed into its screen in the middle of its turn. It waits over the composer, *Held until claude is ready*, with **Send now** (it goes at once, as the agent's next words) and **X** to discard it. Nothing held goes by itself: once the agent is done the line says *claude is ready: review and send*, in amber. Held messages are kept per chat on the phone, through restarts; while the agent asks something they fold to their caption, and Send now waits for the answer.
- **Quick replies**: one-tap chips over an empty box, *continue*, *commit and push*, *run the tests*. Off until **Settings → Quick replies** turns them on; there (or from the pencil at the chips' end) you edit, add or remove them. A chip sent while the agent works is held like anything typed.
- **`/` and `@`**: a `/` at the start of the message lists the agent's own commands (Claude Code's `/compact`, `/review`, `/model`…, codex's, pi's and omp's), with your own and the project's commands and skills read on its machine (`~/.claude/commands`, `.claude/commands` in its folder, the skills folders; codex's saved prompts; pi's prompts and skills). `@` completes a file in the agent's folder: Git's files there (or a walk of the folder when it isn't a checkout), listed once over SSH, matched as you type. A tap puts it in the message.
- **Voice messages**: the mic beside Send records what you say and sends the recording itself to the agent; the phone turns nothing into text. Hold it while you speak and let go to send (slide left to cancel), or tap it to start and tap it (or Send) to send; the box shows how loud you are and the time, and the draft you typed stays as it was. The audio is AAC in an `.m4a` file (about 360 KB a minute, at most 10 minutes; under a second is dropped as too short). For a herdr agent it is copied over SSH to `~/.cache/herdr-android/uploads/<stamp>/voice-message-0m07s.m4a` on its machine and named to the agent as an `@path` mention, the way an attached file goes (held like typed words while the agent works); on Shepherdr it is kept by its service (`/api/upload`) and goes to the space's Assistant as a file sent in its chat, its path after the words. The agent's own program gets the file: Claude Code, Codex and pi can't listen to audio today, so the agent sees a voice message's file and its length but not what you said, unless it has its own way to transcribe it on the machine. In the chat it is a voice bubble with play, its length, and how far along while it plays; one that couldn't be copied or sent stays on the phone with why, and goes again when the chat opens, when the machine comes back, or on **Retry** (or **Discard**). The microphone is asked for the first time you tap, with a line saying why; refused, the composer says so and offers **Allow** (or the phone's settings, once Android won't ask again). Recording and failures are logged under the tag `HerdrMic`. The Assistant on Shepherdr has the same mic; herdr-projects 1.x's Coordinator has none.
- **The model pill**: beside **+**, *Opus 5.5 · 62%* is the model the agent answers with and how full its context is, with a ring (amber past 80%). It comes from herdr's own fields for the agent (an integration that reports `model` and `context` in its `tokens`) or else the transcript: the model and the tokens its last request filled, of the window Codex states or Claude's usual 200k (1M once a request went past that). A tap shows the details (the tokens, the window, the reasoning level, the model's id, what herdr reports about the agent) and **Usage and limits**, the machine's provider page, with what the agent's provider has left.

## Answering a blocked agent

In an agent's chat, the question a blocked agent asks is docked over the composer, so it never scrolls away with the transcript, and it is made for one thumb:

- **The choices are buttons**: **Yes** first and filled; a permanent allow ("Yes, and don't ask again for npm run commands") as **Always for npm run**, which asks for the fingerprint first (below); **No, tell it…** opens the reply, and the no is pressed only when your words go, which then become the agent's next prompt (X takes it back and the question stays).
- **The agent's footer is buttons too**: "Esc to cancel · Tab to amend" becomes **Amend** (presses Tab, then what you type is added to your answer) and **Cancel** (Esc); other keys it names (`ctrl+e to explain`) get their own.
- **A tag says what it would do**: *runs a migration*, *pushes*, *installs packages*, *edits a file*, in red when it can't be taken back (*deletes files*, *force-pushes*, *discards changes*, *deletes data*).
- **Claude's own questions** (its AskUserQuestion form) list each option with its description and a *Recommended* tag; your own words go in the composer. A multiple choice has boxes and **Submit (n)**; a form of several questions shows its steps as chips (✓ answered, the one asked now, Submit).
- Every press reads the agent's screen again first and presses nothing if the question, or the command it is about, changed; so do the notification buttons and the Needs you cards. While you type, the card folds to one line.

## Fingerprint and app lock

**Settings → Fingerprint and app lock** asks for the fingerprint (or face, or the phone's PIN) before what can't be undone, each one switchable: **Always allow** on an agent's question, **Approve and merge** (also "Approve anyway", and on a Needs you card), **Stop a process** (and the leftovers, and Gradle daemons, on a machine's health), **Remove a worktree**, and **Replace the SSH key** (a new key, or restoring another). Every one asks until you turn it off, and turning one off asks too. A notification's **Approve and merge** or permanent allow opens the prompt over whatever is on screen before it does anything.

**App lock** makes opening the app ask as well (it holds the SSH key to every machine), again after it was away for a while (at once, 1, 5 or 15 minutes), keeps its picture out of the recent apps, and makes a locked phone show its notifications only as "Something needs you", without the question, the command or the buttons. A phone with no screen lock has nothing to ask with: the screen says so, the app lock stays off, and the one-tap actions (always allow, merges) ask for a tap on **Confirm** instead.

## Machine health and cleanup

A machine's screen has a **Health** tile (the busiest of RAM, CPU and its fullest disk, red when it runs low), and **⋯ → Health and cleanup** opens the whole picture, read over the same SSH connection every 5 seconds while the screen is in front:

- **RAM, swap, CPU and every disk** (free space per mount), and a chart of RAM and CPU over the **last hour**. The hour comes from herdr-push, which notes a reading every 30 seconds on the machine; without it the chart fills while the screen is open.
- **The biggest processes** by memory or CPU, each with what it is and whose: Android emulators (with their AVD), QEMU VMs, Gradle and Kotlin daemons, dev servers (vite, next, uvicorn, …), node, the coding agents, herdr; and the board task whose worktree it runs in or was started from (`AND-22 · done`), or the herdr pane and agent it runs under. A tap shows its details and **Stop process** (after a confirmation: SIGTERM, then SIGKILL after 5 seconds, and only while that pid is still the same program).
- **Leftovers of finished tasks**: processes still running for a task that is done or archived, and its worktree still on disk, marked safe to clean. **Stop all leftovers** stops them in one go; each task's sheet stops its own and **Remove worktree** removes the checkout with git (asking again, and saying so, when it has uncommitted changes; through herdr when a workspace is open on it). What other work shares is never a leftover: adb's server, this machine's herdr server, and a Gradle daemon while it builds.
- **Stop Gradle daemons**: every Gradle and Kotlin daemon of yours; the next build starts one again.
- **A warning** when RAM or a disk runs low: under 10% of RAM available, or a disk with less than a tenth free (at most 20 GB, so a big disk isn't "low" with 50 GB left). It names what uses the most memory, on the screen and as a notification through herdr-push (and the app's own looks while closed), which comes down once there is room again (15% of RAM, one and a half times the disk's mark). It needs you, so it comes through quiet hours; a tap opens the machine's health.

The full readings and process labels are Linux's (`/proc`); on macOS the screen shows memory, load, disks and the processes from `ps`, without whose they are.

## Usage and limits

Each machine has its own logins, so each has a **Usage** page (the **Usage** line under the tiles on its screen, or **⋯ → Usage and limits**): one card per AI provider that machine uses.

- **Claude Code** and **Codex** (subscriptions): the 5-hour session and the weekly limits (and Claude's per-model weekly ones), how much is used and when each resets, amber from 80% and red when out.
- **API accounts** whose keys are on the machine: DeepSeek's balance, OpenRouter's spend today and this month and its key's limit, Anthropic's costs (with an admin key), OpenCode Go's key and its limits (estimated from what the machine's agents spent: it has no usage API).
- **Local models** (llama.cpp, say, serving Qwen): local, no limit, and whether the server answers.
- On every card: the board tasks and agents that used it most this week (a tap opens the task) and a 7-day chart.

**At a glance**, the top bar of the Agents screen carries the tightest limit across the machines ("Claude 82% · resets 17:00", amber near it), a tap from its usage page; the status widget shows the same line. **Where agents are chosen**, **Start an agent** on a task lists its agent and the ones the board falls back to, each with what it has left ("18% left · resets 11:00", "$7.63 left", "local, no limit"), to start it with another one; adding a task that starts at once names its agent and what that has left. **Warnings** come as notifications at 80% and 95%, when a limit runs out, and when the board's fallback takes a task over because of a limit ("codex took over from claude: out of allowance until 17:00"); **Limits** in the notification settings switches them off, and quiet hours hold them.

It is all read on the machine, over SSH, by [herdr-usage](host/herdr-usage/README.md) (Python 3, copied there by the app): Claude Code's and Codex's own usage APIs with their own logins, the providers' balance and spend APIs with the keys already there, and the agents' session records for what each task and agent used. No key comes to the phone. Readings are cached on the machine for a few minutes and on the phone until the next one, so the page opens at once; while the app is in front, each connected machine is read again every 10 minutes, and while it is closed its own looks (and herdr-push's warnings) keep the limits current for the widget. A limit whose reset time has passed since it was read counts as fresh everywhere, and an agent whose provider's key or login failed says so in the agent choices. Which source each provider's numbers come from, and why: [`host/herdr-usage/README.md`](host/herdr-usage/README.md).

### Agent logins

Each machine also has an **Agent logins** line under Usage (or **⋯ → Agent logins**): which agent CLIs are signed in there (Claude Code, Codex, pi, opencode, Gemini CLI), how, and when each login runs out, amber when one runs out soon or a key sits in a shell profile, red once one ran out. From there you can sign in again (Codex's device code shows on the phone; for Claude you paste the code its page shows back into the app), paste a key (Codex's, or one pi provider's) or sign out (opencode's logins one by one; its sign-in asks with menus, so the app shows how to run it there), after the fingerprint. Each is that CLI's own command, run on the machine over the app's SSH session by [herdr-logins](host/herdr-logins/README.md); a key or code goes in as the command's input, never on a command line, in shell history or on the phone, and a key pi takes from a command (a keyring) is never replaced. A key exported from `~/.bashrc` (or another profile) gets a warning with the steps to move it; the app edits no profile. A board task whose agent needs a login has a **Sign in** button that opens this.

## Notifications while the app is closed

**Settings → Notifications → While the app is closed** makes your machines tell the phone when an agent needs you or finishes, or a work board task waits for you (ready to approve, review failed, plan ready, pick one, asks you, stopped at a limit, went wrong), like Puggie does. Each notification has its buttons: **Approve** or **Deny** a blocked agent's question, **Reply** to that agent (or to the task's agent, or to the Coordinator about the task), **Approve and merge** (after the fingerprint, when that asks), **Approve plan**, **Continue**. A tap opens the agent's chat or the task; finished work (ready to approve, review failed, plan ready, pick one) opens at its review card. The buttons connect to the machine over SSH while the app stays closed; Approve and Deny read the agent's screen again first and press nothing if the question changed.

Two ways in, both on every machine the app knows:

- **Pushes**, within seconds. Each machine runs [herdr-push](host/herdr-push/README.md), a small Python 3 watcher that the app sets up over SSH as the machine connects. It sends to the phone's [UnifiedPush](https://unifiedpush.org) address, which a push app such as [ntfy](https://ntfy.sh) gives (no library, no Google services), sealed with a key only that machine and the phone know. How to check it, run it by hand or use your own push server: [`host/herdr-push/README.md`](host/herdr-push/README.md).
- **The app's own looks**: an exact alarm every 15 minutes (or hourly, or never) connects to each machine and notices what changed, for a machine without python3 or a push that got lost.

Each state is shown once, however it arrives, and comes down again when it is over (answered on the desktop, the agent back at work). The switch next to a machine in Settings leaves it out.

### What reaches the phone

**Settings → Notifications → What reaches the phone** has a switch for each kind: **Needs you** (an agent asks, a board task waits for your yes or your answer, a machine runs low), **Finished** (an agent ended its turn, an AI quick command is done) and **Went wrong** (a review or a check failed, a task or a quick command went wrong) and **Limits** (an AI provider at 80% or 95% of a limit or out of it, a task handed to another agent because of one; see Usage and limits). Finished is off unless you turn it on: a ping for every finished turn teaches you to ignore the ones that matter. What is switched off isn't shown, and turning it on later starts from then.

- **Hold while I'm at the machine** (on by default): while you typed in herdr in the last two minutes, or a machine's screen is unlocked, notifications arrive silently, without a sound or a pop-up. herdr-push tells, with every push, how long ago someone typed in a herdr window there (the last input of its terminal, as `w` reads it), and whether a desktop session there is unlocked (logind's lock, or a screen locker running; on macOS the console's lock). Anything that touches `~/.local/state/herdr-push/present` counts as typing too, a shell prompt or an editor hook, say. Any machine counts: you are at your desk, whichever machine pings.
- **Watches**: one-shot notifications you ask for. Long-press an agent (**Notify me when it finishes**) or a board task (**When its checks pass**, **When it's done**). Each fires once, also when the thing went wrong instead (the checks failed, the pane closed), and removes itself; the screen lists them, each with a ×. The machine's herdr-push follows what is watched there and tells the phone each change of it, so a watch fires while the app is closed too.
- **In the app**: when another agent starts asking (or a board task starts waiting) while you are in a chat or anywhere but the Agents list, one black line drops from the top, "api-server · needs you: run db:migrate?", with the agent's mark. A tap opens it; it leaves after five seconds; it never shows for the chat or the task open in front. A watch that fires while the app is open shows the same way.

### Live Updates

With **Live Updates** on (Settings → Notifications, on by default), what is at work stays in the notifications while it runs: each running board task and each working agent, with a **Stop** button. On Android 16 (a Pixel 10, say) they are Live Updates: promoted to the top of the shade and the lock screen, with a chip in the status bar.

- **A board task** shows its part of the run on a bar in the board's colours (working, checking, in review, merging), its "doing now" line and minutes left, and the chip says where it is: `40%` while it works (or `12m` left), `Check`, `Rev 2/4` in its second of at most four reviews (the first, and one after each time the work went back, `review.rounds`), `Merge`. Stop asks first (**Stop it** or **Keep going**), then runs the board's `stop`: the task waits for you as stopped, and **Continue** starts it again.
- **An agent** shows what it does and how long it has worked (the chip is that time). Stop presses Esc, as the chat's stop button does. A board task's own agents (its worker, its reviewer, the one picking among attempts) are shown as the task, not beside it: they are known by the names the board gives them and by the task's workspace they work in.

They come from herdr-push, which also sends what runs to a phone that asked for it: at once when something starts, stops or moves on, its progress at most every 90 seconds, and again every 30 minutes while anything runs (a push server counts pushes; ntfy.sh allows a few hundred a day). While the app is open it follows its machines' agents itself too, and its own looks while closed count as well. One that nothing confirmed for 75 minutes comes down by itself, as its machine may be gone. On Android 15 and older they are ordinary ongoing notifications with a progress bar. If Live Updates are turned off for the app in Android's settings, Settings says so and has a button to turn them on.

### Quiet hours

**Settings → Notifications → Quiet hours** holds the notifications that can wait, like Puggie's quiet hours: while the phone is in Do Not Disturb or a mode that turns it on (Bedtime, Driving) or in car mode, or on a schedule you set (from, to, and the days a quiet stretch starts on; the same time twice makes those days quiet all day). An agent that finished and a watch that fired wait, and Live Updates come down. What needs you still comes through: an agent that asks, a board task waiting for you, something that went wrong, a test. When quiet hours end, what was held shows as one summary notification (or as itself, when it is only one), and the Live Updates come back; one that was over by then (the agent back at work) is left out. Android tells a closed app nothing when Do Not Disturb ends, so the app looks every quarter of an hour while the phone is quiet; a schedule's end has its own alarm. Whether what comes through makes a sound is still Android's to decide: allow Herdr in the Bedtime or Driving mode to hear it.

## The brief

The **Brief** (the newspaper icon above the agents, or **Settings → Brief**) tells what the agents and the work board did since you last looked, on every machine: what merged (and research reports that are ready), what went wrong, what waits for you now (tasks to approve or answer, plans, agents that ask), your own agents that finished, and what is still at work. Each line opens its task on the board or its agent's chat. "Since you last looked" is since the app was last on screen, or a brief was read aloud to its end; **Last 24 hours** is there too.

- **At the times you choose.** **When the brief comes** sends it as a notification, at up to four times (say 07:30, and 17:30 for the drive home), every day or on the days you pick. Its lines say what failed, what waits and what merged; a tap opens it.
- **Read aloud, for the drive.** **Read aloud** (on the brief, and on its notification) reads it with the phone's voice, over the music: what went wrong, what waits for you, what was done, then what is at work. Titles rather than task keys, at most five of each. The reading goes on with the screen off or the app closed; its notification and the brief's bar have **Next** and **Stop**. **Read it aloud as it comes** can also read the scheduled brief by itself, in the car (car mode: Android Auto or a car dock) or always.

To put it together the app reaches every machine over SSH, as for notifications while it is closed. Where [herdr-push](host/herdr-push/README.md) runs, its journal tells everything that happened, with times (herdr-push keeps a week of it: each task done, waiting for you, gone wrong, started or added, and each agent that finished or asked). Elsewhere the brief compares how the machine stands now with what the app showed you when you last looked, and says so under the machine; a machine it can't reach is named, with why.

## Code

The code on your machines, to read and comment on from the phone. **Code** opens from the code icon on the Machines tab (choose a machine), from a machine's **⋯** menu and a workspace's actions, from an agent chat's **⋯** (**Browse the code**: the folder it works in) and from a board task (**Changes and files**: its worktree, at its changes).

- **Browse.** A machine's repositories, each with its checkouts: the main one and the worktrees (an agent's, a task's), with the agents at work in each. **Open a folder** takes any other path. A checkout's files are folders; **Go to file** finds a file by a few letters of its name; the search icon searches the code itself, on the machine (`git grep`: words or a regular expression, with or without case).
- **Read.** Numbered lines in the herdr web UI's code colours, long lines wrapped or scrolled sideways, the text size you set (**Aa**), **Go to line** (**#**), Markdown as it reads (and its source a tap away). A big file opens at once: its first 4 MB, said so, drawn a line at a time as you scroll.
- **Changes.** Git's status (branch, ahead and behind, what isn't committed), the diff against the branch it left (`main`, or whatever `origin/HEAD` names), or only what isn't committed yet, new files included; a file's diff with both line numbers, stepping through the files. **History** lists the commits, those not in `main` yet first, and each commit's own diff.
- **Comment.** Tap a line number (or long-press a line), then another to take the lines between, in a file or in a diff, and **Comment**. It goes to the agent at work in that checkout, the task's agent (`tell`) or the Coordinator, as a review note: the path, the lines, the quoted code (a diff's with its `+` and `-`) and your words. **Keep for later** adds it to the **Comments** waiting on that machine (the speech-bubble icon, with a count), to change, drop, or send together as one message.

Everything is read over the machine's SSH connection with `git` and the shell, and nothing is changed there: Git runs with `GIT_OPTIONAL_LOCKS=0`, so not even `git status` touches the index. Only sending a comment does something, and that is a message.

## Quick commands

Your own scripts, one tap away: ask Claude (or any agent) to write a script into a commands folder, and the app shows it as a quick command. **Quick commands** (the ⚡ icon) opens from a machine's screen, a workspace's actions, an agent chat's **⋯**, a board task (under **Changes and files**) and a file in **Code** (for `{{file}}` and the lines chosen there). Tap one to run it; long-press (or **⋯**) to pin it to the top, run it in a new herdr pane, read its file or copy its path.

**Where they live, on the machine:**

| Folder | Shows |
| --- | --- |
| `<repo>/.herdr/commands/` | in that repository's workspaces, worktrees, agents and tasks (a worktree's own folder first, then its main checkout's) |
| `~/.config/herdr/commands/` | everywhere on that machine |

A project's commands come first; one with the same name as the machine's wins. A command is an **executable file** (a script in any language) or a **`<name>.prompt.md`** file (an AI command, below). The list is read again each time it opens and when the machine connects; pull it down (or **↻**) to read it again.

**The header** is a comment block at the top of the file, one `# key: value` per line (after the `#!` line). Without one, the file's name is the command's name.

```sh
#!/usr/bin/env bash
# name: Deploy staging
# desc: Builds and pushes to the staging server
# icon: rocket              # a Lucide icon name (git-branch, test-tube-diagonal, hard-drive…)
# confirm: true             # asks before it runs
# args: Which branch?       # asks for arguments in a small form first
# pane: true                # runs in a new herdr pane (long or interactive ones)
```

**Running one.** It runs on the machine in the checkout's folder (the home folder for a machine command), detached from the phone: its output streams into a sheet (a long log shows its end, the last 256 KB, and says how much is left out), with how it ended (exit status, how long), **Stop**, **Copy** and **Run again**. Arguments typed in the form come as its arguments (split like a shell's words, quotes keep spaces) and whole in `$HERDR_ARGS`; it also gets `$HERDR_REPO`, `$HERDR_CWD` and `$HERDR_COMMAND`. **Run in a new pane** types it into a new tab of the workspace instead and opens its terminal. Every run is kept on the machine (`~/.local/state/herdr-android/quick/<run>/`, the newest 80), so closing the app or losing the connection doesn't stop it: **Runs** (the history icon in the sheet, or the machine's **⋯ → Quick command runs**) lists them with what they printed, to open, run again or remove.

**AI commands** are prompts run by a durable AI worker on the machine ([`host/herdr-quick`](host/herdr-quick/README.md), on [pi-durable](https://www.npmjs.com/package/@earendil-works/pi-durable)): `<name>.prompt.md`, the same header (its keys at the top, so a Markdown heading starts the prompt) and the prompt as the rest of the file.

```markdown
# name: Summarise today
# desc: What changed in this repo today
# icon: newspaper
# model: pi/deepseek/deepseek-flash   # or claude/claude-opus-5-5; none: pi's default model there
# tools: read-only                    # read-only (the default: files, search, git history), shell (and writing files), web
# args: Anything in particular?
# timeout: 10m                        # its limits: 10 minutes and 200k tokens unless it says
# tokens: 200k

Summarise what changed today ({{date}}) in {{repo}} on {{branch}}. {{args}}
```

Placeholders: `{{args}}` (what was typed; added at the end when the prompt has none), `{{repo}}`, `{{worktree}}`, `{{branch}}`, `{{selection}}` (the lines chosen where it was opened, else the clipboard), `{{file}}`, `{{clipboard}}`, `{{machine}}`, `{{date}}`. Models: `claude/<model>` is Anthropic's API (with `ANTHROPIC_API_KEY` there or pi's key for it), `pi/<provider>/<model>` a provider of pi's own `~/.pi/agent/models.json` with pi's keys. Tapping one starts the worker in the checkout's folder; the app shows what it does and its answer as it is written (Markdown), and reattaches when you come back. It survives the phone going away, the app closing and the worker being killed (its conversation is kept in the run's folder, and **Carry on** picks it up where it stopped). When one ends while the app is closed, a notification says so (through herdr-push, or the app's own looks), and a tap opens it. Its answer can be copied, run again, **sent to the Coordinator** (into its message box) or **made a board task**. The first AI command on a machine installs pi-durable with npm (it needs Node.js 22 or newer there).

**New command** (in the sheet) asks an agent to write one for you: what it should do, a script or an AI prompt, for this project or everywhere; it goes to the agent the sheet was opened from, the task's agent, or the Coordinator, as a request naming the folder and the header.

## The work board

The **Board** tab shows every machine's work board in one list, grouped by machine (what needs you, then what is at work and what is to do), with a chip per machine; a task opens on its machine's board, a machine's heading opens that whole board. Next to the Coordinator chat, a machine with herdr-projects gets its own **Board** screen: the task list as the board shows it (short key such as `AND-7`, title, status in the board's colours, agent, the "doing now" line, progress and minutes left, `[research]` marks, PR links), with **Needs you** (approvals, plans, pick one, errors, questions) first. Search runs the board's `--query`; chips filter by repo and machine; the menu shows archived tasks. Tap a task for its details (failure and next step, reviews with findings and severity, handovers, plan, attempts, PR and report links, limits and usage); long-press for its actions: start, tell, stop, retry, fix, review, finish, approve (and approve anyway, which shows the failure and asks for a tick), approve or send back a plan, pick an attempt, archive/restore, clear, edit title/description. **+** adds a task. It reads `herdr-projects list --json` and `details <id>` and runs the board's own CLI commands over the app's SSH connection, every few seconds while the screen is in front and not at all when backgrounded; a refused command shows the board's own reason.

### Finished work, one card at a time

**Review finished work** (at the top of a board while something waits for your yes, in its **⋯** menu, on a task's long press, and the tap on such a task's notification) goes through every task that waits for you, across all your machines' boards, as cards: ready to approve, review failed, plan ready, pick one, and work whose merge is blocked (its pull request's checks failed). Swipe to the next; the bar says which card this is and how many are left.

Each card has the task's key, title and status (and its machine, with several boards); what failed, when something did; **what changed** (files, lines added and removed, the biggest few; a tap shows every file, each opening its diff in **Code**); the agent's **closing note**; the newest **review**, its verdict and findings by severity; the **checks**: the board's local check and the pull request's checks on GitHub, each a tap from its run; and the pull request and report. A plan shows the plan; a pick shows the attempts, each with **Pick**.

Its actions: **Approve and merge** (**Approve plan** for a plan), **Send back** with your words for its agent (`tell`; after a failed review, its findings can go instead), **Stop**, and **Approve anyway** in place of Approve when the review or a check failed: it lists what failed and merges only once you tick that you know. A card acted on goes, and the next one shows.

What `details` doesn't send comes from the board's own record of the task on that machine (`~/.herdr-projects/data/tasks/<repo>/<n>/`: the closing note, whether the check passed), read only, and `gh pr checks` there for GitHub's checks (without `gh`, the board's last word on them).

### What an agent did, step by step

**Activity timeline** (in an agent chat's **⋯**, and in a board task's details) follows a task without reading its whole chat or terminal: every step its agents took, grouped by turn.

- **Turns.** Each starts with what started it: your message, or one of the board's (it started the agent, started a review round, sent the findings back, asked it to continue), and says when, how long it took, which agent and model ran it, and what it did (`12 commands, 7 edits, 3 tests`). A reviewer's turns say their round. Long histories show their newest turn open and the others folded; a tap opens one.
- **Steps.** Messages, commands with their exit status and how long they ran, files read and searched, edits (a tap shows the diff), tests and checks (a build, a linter: passed or failed, with the line that says so, `61 tests completed, 1 failed`), web lookups, other tool calls and the plan. A tap shows a command in full with what it printed.
- **The board's events between them**: added, started, asked you, hand-overs from one agent to another (and why), each review round's verdict with its findings, the report, stopped, went wrong, merged.
- **Filters and failures.** Chips keep messages, commands, edits, tests or failures only, with how many there are; **Jump to a failure** (and the alert icon above) goes from one failed step to the next, opening its turn.
- **Live.** While the agent works the timeline grows as it takes each step (**Live** in the bar); it reads the board's record again now and then, so a review round that starts appears. A finished task's timeline is there too, as long as its sessions are on the machine.

It is built from what the agents' harnesses record, read over SSH: Claude Code's sessions (`~/.claude/projects`), codex's (`~/.codex/sessions`) and pi's or omp's (`~/.pi/agent/sessions`), each step with its times, exit status and diff; and the board's record of the task (`~/.herdr-projects/data/tasks/<repo>/<n>/task.json` and what it typed to its agents, `said-by.jsonl`), which names every session the task's agents had, across hand-overs. Nothing there is changed. Sessions are pruned on the machine before they are sent (with `python3` where there is one: the copies of API requests and whole files Claude Code keeps beside an edit go, long outputs are cut), so a 20 MB session comes as a megabyte or two; of a very long one only the newest part is read, and the timeline says so. An agent's chat opens its task's timeline when it works for a board task (its worker or reviewer), else its own session's.

### Routines: board tasks on a schedule

The board's automations, as **Routines** (a board's **⋯** menu, the calendar icon on the **Board** tab for every board at once, and the **Routines** row on a machine's screen): a task the board adds by itself, on a schedule (`Mondays at 07:00`, `Every 2 hours`, `Daily at 02:00`, a cron line, in the machine's time zone) or when something happens to a task on the board (it is added, waits for approval, fails its review, goes wrong, needs you, is merged; in its repo or in any), each time putting an agent on it.

- **The list** groups them by what starts them and keeps the paused ones last. Each says its trigger in words, its repo and whether it is research only; how its last run went (`Last run: AND-9 ready to approve · 5 days ago`, `Last run asks you (API-3) · open its chat`, a skip and why, a task that got no agent), a tap on that line opening the task's chat (else the task); a **pause** switch and **Run now**. The machine's row says how many are on and when the next runs, or how many need you.
- **One routine** shows when it next runs, what each task is told, its repo, folder, agent and kind, and every firing the board kept: the task it made and how that task stands, or why it was skipped, what started it and when. **Edit** and **Remove** (which asks; the tasks it made stay).
- **New routine** and **Edit** take its name (its tasks' title), what each task is to do, the repo (fixed once made, as the board keeps it), when it runs, **Research only**, and the agent its tasks get (the board's own, or one of its fallbacks). The board's refusal ("That name reads like an automation's id…") shows on the form and keeps it.

It is the board's own command over SSH and nothing else on the machine: `herdr-projects automations --json` and `automations show <id> --json` to read, `automations add`, `change`, `on`, `off`, `run` and `remove`, and `set`/`unset worker.agent --automation <id>` for the agent. The board itself fires them (its keeper, once a second), whether the phone is there or not; a firing while the last task still has an agent on it is skipped, and more than ten in an hour turns the routine off (the list says so).

## The work board's Coordinator

On a machine with [herdr-projects](https://github.com/eliasstravik/herdr-projects) (its launcher at `~/.herdr-projects/data/bin/herdr-projects`, or `herdr-projects` on the PATH), the app shows a **Coordinator** chat above that machine's agents. It reaches the board the way another machine's board does, over the same SSH connection: `herdr-projects remote list` for the conversation and where the Coordinator stands, and `herdr-projects remote dispatch` for a message, which joins the same queue as the words typed in the board's own box. So the phone and the desktop show one conversation. **Start agents**, the switch beside Send, is the board's Tab: what the Coordinator adds from that message gets an agent at once. What you type stays in the box per chat, also when you leave the app, until it is sent. The mic beside it takes a voice message, as in an agent's chat (above): the words go into the box to check before Send. **+** attaches files from the phone, in both chats: each is copied to that machine (`~/.cache/herdr-android/uploads/`) and named in the message as `@path`, which the agents read. Photos and screenshots go the same way, after a look (below).

The Coordinator's answers name tasks by their keys (`AND-6`, `API-3`); each one on the board shows as a row with its live state (`working · 3%`, `asks you`, `done`) that opens it there. A list of tasks becomes rows in its place, each with what the answer says of it under the title (its sub-items and indented lines too, the title not twice); a list that mixes tasks with other items stays as written, and a task named in passing gets its row after the answer (not one named in code). A strip under the bar shows the board at a glance (`3 need you · 3 working · 1 queued`, counted as the Board tab counts them) and opens it. Above an empty message box, suggested asks that are a next step on that board now: **What needs me?** when something does, **Start the queued ones** when tasks wait their turn, and **Status of** the open task the conversation named last; a tap puts the words in the box, to send or change.

## Photos and screenshots

Both chats, an agent's and the Coordinator's, take pictures: **+** offers **Take a photo** (the phone's camera app), **Photos** (Android's photo picker, several at once), **Latest screenshot** (the newest in the phone's Screenshots folder; it asks once for leave to see your pictures, and on Android 14 leave for only some is enough) and **File** (a picture picked as a file counts too). Up to 10 go with one message.

- **Made smaller first.** A photo is brought down to 2048 pixels on its long side and turned upright, as JPEG; a screenshot stays PNG so its text keeps sharp edges (unless that makes it over 3 MB). HEIC and WebP become what the agents read.
- **Shown before they go.** They sit in the message box as thumbnails, each with a × to take it out. A tap opens it full screen to **Crop** (drag the frame's corners, edges or middle) or mark it up: **Draw**, **Arrow** and **Highlight**, in six colours, with Undo. **Done** puts the marked-up picture in its place.
- **Copied at once.** Each is copied to the chat's machine as soon as it is added (or once the machine connects; a clock on the thumbnail says it waits) and Send waits until all are there. When the message goes, they are named at its start as `@path` mentions, the way herdr takes a file: herdr passes agents only text and keys, so a picture goes as a file. Claude Code reads an `@path` picture as the image itself; other agents open it with their own image tool.
- **In the chat.** Your turns show the pictures they name as thumbnails (one large, several in a grid) over your words; a tap shows it full size, to pinch and zoom. A picture sent from this phone is shown from the copy it kept; any other one named in a message (sent from the desktop, say) is read back from the machine once (`base64`, at most 12 MB) and kept.
- **Kept like the draft.** The pictures waiting in a message box stay through leaving the chat and the app being closed, until they are sent or taken out.

## Asking from anywhere

The Coordinator is a tap away from outside the app too:

- **Share to it.** Share a link, some text, an error or a screenshot to **Herdr Coordinator** from any app's share sheet. It lands in the Coordinator chat's message box: the words at the end of what you had typed, a picture among its pictures (made smaller, to look at, crop or mark up before it goes, as above), any other file copied to that machine and named as `@path`, as **+** does. You read it, add to it, and send it, so the Coordinator can make a task of it. With several machines the app asks which one first (the one used last on top); a machine without the work board can't be picked. A share waiting for a machine, and files waiting for a machine that isn't connected, are kept through the app being closed: the app asks again when it next opens; a picture waits in the message box, and the chat says which other file waits and attaches it once the machine connects.
- **Send selected text.** **Send to Coordinator** sits next to Copy in the menu of selected text, and does the same with just the text.
- **The Quick Settings tile.** **Coordinator** opens the chat at once: the Coordinator used last, or the only machine that can have one; otherwise it asks. Under its name it says what needs you ("2 need you · 3 working").
- **The launcher's shortcuts.** A long press on the app's icon offers **Needs you** (with its count), the **Coordinator** used last, **Start work**, and the two agents whose chats were opened last.
- **Two home-screen widgets.** **Ask the Coordinator** opens the chat as the tile does. **Herdr** shows the counts across your machines, such as "2 need you · 3 working", and when they were last known; a tap opens the board with the most tasks waiting for you (or the agents, when it is an agent that asks).

The counts are what the app knows: what it sees while open (and each board's list, read every 5 minutes while the app is in front), its own looks while closed, and the machines' pushes (see Notifications above). An agent that needs you and the board task it works for count once, as do a task and its own agents at work, and another machine's task counts on that machine. What works and nothing confirmed for 75 minutes stops counting.

## Floating chats

One agent's chat or the Coordinator chat can float over other apps as an Android bubble (Android 11 and newer), to steer it while reading something else. **Float over other apps** is in an agent chat's **⋯** menu and in the Coordinator's top bar; an agent's notification has **Float chat** too, when it has room for a third button (a finished agent, one that asks something without a yes or no), and so has the Live Update of an agent at work (the Coordinator's own agent floats the Coordinator chat). The bubble is the chat itself, with its draft, attachments and terminal; **Open in the app** (in its menu, or the Coordinator's top bar) opens it full size. Each chat gets its own bubble, and Android keeps them as conversations: dragging one away closes it, and the launcher's long press on the app lists them.

Android lets an app's chats bubble only once you allow it. The first time, the app says so and opens Herdr's bubble settings: choose **All conversations can bubble**, and the chat floats as you come back. With **Selected conversations**, the chat shows as a notification instead, whose bubble button floats it (and marks it as one that may).

## Accessibility

Everything a long press opens is also offered to TalkBack as actions on the card or row: on a question's card **Answer yes**, **Answer no** (or the menu's two choices), **Reply…**, **Open terminal**, **Why this state?**, **Mute notifications** and **More actions** (the long-press sheet); on an agent's row its triage (seen, pin, snooze, mute) and the same; on a board card its board actions. Back is predictive (Android 13 and up shows where it goes while your thumb is still on the edge). Answering an agent, approving and sending give a light haptic. Chats and the terminal each have their own text size, on top of the phone's font size (**Settings → Text size**; pinching the terminal sets its size too). When the terminal asks for a password (`sudo`, `ssh`, a key's passphrase, a card's PIN), a masked field shows over its keys, which types it with Enter and keeps it nowhere.

## Big screens

On a window 840 dp wide or wider (a tablet, an open foldable, a large window or desktop windowing), the app shows three panes side by side, like Puggie: the agents list (with Agents, Board and Machines under it), then what you opened from it (an agent's chat, a review card, a terminal, the Coordinator, the Board tab, a machine, Settings), then a machine's work board. What is open in the middle is marked in the list, and opening another agent replaces it instead of piling up; Back closes it, and then a task open on the board. A board opened from a notification, the widget, the Board tab or a machine's Board card shows on the right, with its task open (a board still open in the middle, from before the window grew, moves there). A narrower window keeps the phone layout. Rotating or resizing across the line keeps what is open as it was: the chat, its draft and scroll, the board's search, filters and open task (kept while a narrow window hides the board, and back when it is wide again).

## Screen previews

UI changes are checked, and shown in review, with screen previews instead of an emulator: the app's real Compose screens rendered on the desktop JVM against a fake herdr machine with fixed demo data, in dark and light.

```sh
./gradlew -p tools/ui-preview render                     # every scene → tools/ui-preview/build/shots/*.png
./gradlew -p tools/ui-preview render -Ponly=board,chat   # some of them
```

It needs JDK 17, no Android SDK, and takes about half an hour for all 200 scenes (agents, search, start work and its folder browser, machines, adding one by its code (found, not answering) or by hand, the code browser (repositories, files, a file, a comment, changes, a diff, history, search, comments waiting), quick commands (a machine's, a project's with one pinned, arguments, a command's menu, a run, its runs, an AI answer and one at work, a long log, a new one, none yet), a machine, a machine's health and cleanup, agent chats, pictures in a chat (what + offers, two screenshots in the message box, one marked up and one cropped, sent ones in the chat and one full size), the Coordinator, floating chats, the board and a task, routines (the list, every board's, one with its firings, a new one on a schedule and on a board event, one edited, one removed, none yet), the activity timeline (a task's, its failures, a command opened, an edit's diff, a jump to a failure, a review's verdict, an agent's at work), finished work's review cards (a passed one, a failed one, its files and a diff, approve anyway, send back, a pick on another machine, none waiting), the brief and when it comes, a machine's usage and limits (its providers, one out of its allowance, one whose reset passed, a reading that failed, the agents to start a task with, one whose key was refused), the terminal (a password asked there, sticky modifiers, the F-keys, the key bar's editor and a bar of one's own, selected text quoted in a chat or sent to the Coordinator), a workspace's dev servers (a page through SSH, at desktop size, a screenshot in an agent's message box, none serving), settings with its notifications and text sizes, TalkBack's actions on a card, the dialogs and sheets, and the three panes of a wide window, also across a rotation). CI renders them too, a quarter of them on each of four runners, and uploads them as the `herdr-screens-<run>` artifact. See [`tools/ui-preview/README.md`](tools/ui-preview/README.md) for how it works and how to add a scene, and [`AGENTS.md`](AGENTS.md) for how changes are reviewed.

## Shepherdr

On a machine running [Shepherdr](https://github.com/eliasstravik/shepherdr) 3.x (its service, `shepherdr.service`), the board is Shepherdr's: the app reaches the service on the machine's `127.0.0.1:7788` through the SSH connection (a port forward; `curl` and `bun` there where the SSH server forwards nothing), with the token in `~/.herdr/shepherdr/token`, read over SSH. It reads what the 3.1 web app reads (`web/shared/types.ts`):

- **Spaces** of every machine the service links over herdr-core, each with its **Assistant**'s chat: words, tasks' news lines, question cards (a plan, several questions on one card), what an agent asks on its own card headed by its task, Shepherdr's notice when it can't read a chat, and its working line.
- **Tasks** in Shepherdr's own states and order (Needs you, Ready, Merging, Working, Queued, To do, Done), its second line, % done and the minutes left under the dot; tasks no space works in under their list's name.
- **Accept**: Shepherdr's Accept card (its summary, commits and files, checks, proof, details; the words of its `core/accept.js`) before `herdr-task accept`, from the task, its news line, Needs you and the board.
- **Artifacts**: recipes (`core/recipe.js`) drawn natively: text, lists, tables, charts, numbers, pictures, before and after, code, callouts, tabs, folded sections, and choices, forms and buttons that answer the agent; Mark up notes one block. A panel only the Herdr window runs says so.
- **Voice calls** with an Assistant or a task's agent (the phone icon), through the machine's ElevenLabs voice agent (`core/voice.js`, `/api/voice`): 16 kHz audio both ways, echo cancelled so you can talk over it.

Shepherdr 2.x isn't supported any more. The code is `core/…/shepherdr/`; its tests read 3.1.2's own answers, scrubbed (`core/src/test/resources/shepherdr/`).

## Layout

- `core/`: pure Kotlin/JVM: SSH, herdr API, herdr-core's API (`core/hc`, [`docs/HERDR_CORE.md`](docs/HERDR_CORE.md)), host sessions, terminal emulator and its key bar (`core/terminal`), reading code (`core/code`), quick commands (`core/quick`), dev servers and port forwarding (`core/ports`, `core/ssh/PortForward.kt`). `./gradlew -p core test`.
- `app/`: the Android app. `ui/theme` and `ui/design` hold its look and shared components ([`docs/DESIGN.md`](docs/DESIGN.md)).
- `tools/ui-preview/`: the screen previews. `./gradlew -p tools/ui-preview render`.
- `host/herdr-push/`: the watcher that runs on a machine and pushes to the phone (Python 3; core carries it as a resource, and its tests run it).
- `host/herdr-usage/`: the AI providers' usage reader, run on a machine for its usage page and herdr-push's warnings (Python 3; core carries it, and its tests run it).
- `host/herdr-logins/`: the agent CLIs' logins on a machine, and signing them in or out there with their own commands (Python 3; core carries it, and its tests run it against fake CLIs).
- `host/herdr-quick/`: the AI quick commands' durable worker (Node.js and pi-durable; core carries it, and the app copies it to a machine as it runs one).

## Contributing

Bug reports, ideas and pull requests are welcome: [`CONTRIBUTING.md`](CONTRIBUTING.md) says how to build and run the app, run the tests and the screen previews, and send a change.

## License

[MIT](LICENSE). The design tokens, icons, fonts, agent marks and the herdr logo it uses keep their own licences, listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
