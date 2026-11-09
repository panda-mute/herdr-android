# Herdr for Android: user guide

What the app does and where to find it. To install it and add a machine, see the [README](../README.md).

The app has a drawer of spaces on the left, grouped by machine, and three tabs at the bottom: **Chat**, **Tasks** and **Artifacts**. **Settings** and **Add machine** are at the foot of the drawer.

- [Tasks and Needs you](#tasks-and-needs-you)
- [Agent chats](#agent-chats)
- [Pictures and voice](#pictures-and-voice)
- [The Assistant and the board](#the-assistant-and-the-board)
- [Start work](#start-work)
- [The terminal](#the-terminal)
- [Search](#search)
- [Code, pull requests, browser and docs](#code-pull-requests-browser-and-docs)
- [Machines](#machines)
- [Notifications](#notifications)
- [The brief](#the-brief)
- [From outside the app](#from-outside-the-app)
- [Change the app from the phone](#change-the-app-from-the-phone)
- [Settings and security](#settings-and-security)
- [Big screens and accessibility](#big-screens-and-accessibility)

## Tasks and Needs you

The **Tasks** tab is one list across every machine. In order:

1. **Needs you**: agents that ask a question, tasks that ask or want a secret, Assistants that ask.
2. **Ready**: finished tasks, each with **Accept**.
3. The rest by state: Working, Queued, To do, Done and Idle.

A filter field and chips (All, Needs you, Ready, Working…) narrow the list. An agent and the task it works on show as one row.

Rows carry their answers:

- A question has **Yes**, **No** and **Reply**. A longer menu opens in the chat.
- A task has its question's choices, **Give it** for a secret, or **Accept** when it is ready.
- Before pressing anything, the app reads the agent's screen again. If the question changed, it presses nothing.

Finished agents wait under **Done** until you open their chat, swipe them right, or tap **Mark all seen**.

Swipe an agent left to **Pin** it to the top. A long press opens its sheet, with Pin and its space's **Notify**:

- **All**: its questions and each finish.
- **Needs me**: only its questions.
- **Off**: nothing.

A long press on a space in the drawer sets the same. A question always shows in Needs you, whatever Notify says.

The **+** offers **New task**, the **Assistant** and **Start an agent**.

## Agent chats

Tap an agent to open its chat. It shows the agent's own transcript (Claude Code, Codex, pi or omp), not its terminal.

**Reading**

- Each turn's work folds into one line, such as *Worked for 2m 36s · 1 edit · 1 command*. Tap it to open it. Failed calls are counted in red.
- Each turn shows its time. File paths are short, and edits show what they changed (`+12 −2`).
- Coming back after five minutes or more, a **While you were away** card says what happened.
- If you scroll up while the agent writes, a pill counts the new messages.
- **Activity timeline** (in the chat's **⋯**) lists every step the agent took, by turn: commands, edits, tests and failures, with filters and **Jump to a failure**. It is not available for herdr-core agents.

**Writing**

- A message sent while the agent works is **held** over the composer until the agent is ready. **Send now** sends it at once; **X** drops it. Nothing held goes by itself.
- **Quick replies** are one-tap chips (*continue*, *run the tests*). Turn them on and edit them in **Settings → Quick replies**.
- `/` at the start lists the agent's commands and skills. `@` completes a file in the agent's folder.
- The **model pill** beside **+** shows the model and how full its context is. Tap it for details and the machine's usage limits.

**Answering a question**

When an agent asks, its question is docked over the composer.

- The choices are buttons. **Yes** comes first. A permanent allow shows as **Always for …** and asks for your fingerprint.
- **No, tell it…** opens a reply. The no is pressed only when you send your words.
- Keys the agent offers, such as Tab to amend or Esc to cancel, become buttons.
- A tag says what the action does, such as *pushes* or *installs packages*. It is red when it can't be undone (*deletes files*, *force-pushes*).
- Claude's own multiple-choice questions show each option with its description, and a **Submit** for several answers.

## Pictures and voice

**Pictures.** In any chat, **+** offers **Take a photo**, **Photos**, **Latest screenshot** and **File**. Up to 10 go with one message.

- Photos are made smaller and turned upright. Screenshots stay sharp.
- Tap a thumbnail to crop it or mark it up with **Draw**, **Arrow** and **Highlight**.
- Pictures are copied to the machine as you add them. The agent gets them as files (`@path`). Claude Code sees the image; other agents open the file.
- Pictures in the chat open full size on a tap.

**Talk to type.** Tap the mic, speak, and tap **Done**. Your words go into the message box to edit before you send. Where the machine runs Shepherdr 3 with whisper, the machine writes it down; elsewhere the phone does.

**Voice messages.** Hold the mic, speak, and let go to send the recording itself. Slide left to cancel. The agent gets an audio file. Most agents can't listen to audio, so they see only that you sent one, unless the machine can transcribe it.

## The Assistant and the board

A machine's tasks come from one of two boards, detected per machine:

- [**Shepherdr**](https://github.com/eliasstravik/shepherdr) **3**, where its service runs. Shepherdr 2 is no longer supported.
- [**simple-herdr-projects**](https://github.com/makefunstuff/simple-herdr-projects), where `shp` is installed. It has no Assistant, artifacts, Autopilot or voice.

To pick one yourself: **Edit machine → Tasks**.

**The Assistant** (Shepherdr only). Each space has an Assistant you talk to in the **Chat** tab. It makes tasks from what you ask, and its chat shows the tasks' news, its plans and questions, and **Accept** cards. You can reply to a message, react to it, find in the chat, hear the Assistant read aloud, or call it by voice.

**A task** opens with tabs: **Chat**, **Files**, **Changes**, and, when it has them, **Pull request** and **Browser**. A ready task has **Accept** on top, with Shepherdr's summary of commits, files and checks.

**Artifacts** (the third tab) are what the agents made for you: tables, charts, before-and-after shots, forms you answer. Where the machine has no Shepherdr, the tab shows its [docs](#code-pull-requests-browser-and-docs).

**Autopilot** lets a space's Assistant keep working on its own. Turn it on in the Assistant's header. **Settings → Autopilot** shows how it runs on each machine.

## Start work

**Start work** asks what you want, then how and where. Open it from the **+** on Tasks, a space's long press, search, or the launcher icon's long press.

- **Board task**: your sentence goes to the space's Assistant, which makes the task.
- **Agent now**: an agent starts in the folder you pick, with your sentence as its first message. It offers the agents installed on that machine.

On a machine that runs only the Herdr desktop app, the agent starts as a new tab in the space for that folder, or under **Other terminals**. Its chat opens at once, with your sentence held over the box. The sentence goes in once the agent is ready. If the agent asks something first, or never says it is ready, the sentence stays held for you to send.

Folders you used before come first. **Browse…** walks the machine's folders. The phone never starts an agent in a mode that skips its permission prompts.

## The terminal

Any pane opens as a live terminal, sized to the phone.

- **The key bar** has Esc, Tab, ^C, arrows, **fn** for F1–F12, and more. Ctrl, Alt and Shift are sticky: tap once for the next key, twice to keep them on. **Hold Space and drag** to move the cursor.
- **Edit the key bar** with the pencil at its end: add, remove and reorder keys, or build your own shortcut. The bar is the same in every terminal.
- **Select text** with a long press. Then **Copy**, **Quote in chat** (into an agent's message box) or **Send to the Assistant**. Nothing is sent until you send it.
- The **⋯** menu has **Find in scrollback**, **Copy screen**, **Keep screen on** and **Reconnect**.
- When the terminal asks for a password, a masked field appears. It types the password and keeps it nowhere.
- Pinch to change the text size.

## Search

The search icon on Tasks, the drawer or a machine opens one search (**Ctrl+K** on a keyboard). It finds agents, tasks, machines, workspaces and words in recent chats. Chips filter to **Needs you**, **Working** or **Done**.

Words in chats are searched on the machines, in each agent's transcript, once you type three letters. Below the results, **New task about "…"** starts work on your words, and **Open a terminal in …** opens a shell in the workspace found.

## Code, pull requests, browser and docs

**Code.** A task's **Files** and **Changes** tabs show the folder it works in. An agent's **⋯ → Browse the code** opens the same for its folder.

- Browse folders, **Go to file**, or search the code.
- Read files with line numbers and syntax colours. Markdown is rendered.
- **Changes** shows the branch, what isn't committed, the diff against `main`, and **History**.
- Tap a line number, then another, and **Comment**. The comment goes to the agent as a review note. **Keep for later** collects comments to send together.
- Nothing on the machine is changed by reading.

**Pull request.** The task's pull request as GitHub shows it: state, checks, threads and conversation. **Approve**, **Request changes**, **Comment**, **Reply** and **Resolve** post as the machine's `gh` login. On your own pull request only Comment is offered.

**Browser.** The task's agent browser, live. Touch the page to take over; **Hand back** returns it to the agent. **Open browser** starts one.

**Docs.** The herdr.doc documents agents write (investigations, runbooks, reports), read from the machine's `~/.herdr/docs`. A draft fills in while you read it. Find them in a machine's **⋯ → Docs**, or the Artifacts tab where there is no Shepherdr.

## Machines

Tap a machine in the drawer. Its screen shows three tiles, then its spaces and panes.

**Health.** RAM, CPU, swap and every disk, with a chart of the last hour. It lists the biggest processes and what they are (emulators, Gradle daemons, dev servers, agents). Tap one to **Stop process**. **Stop Gradle daemons** stops them all. When RAM or a disk runs low, you get a warning. Full detail on Linux; less on macOS and Windows.

**Usage.** One card per AI provider the machine uses:

- Claude Code and Codex: session and weekly limits, and when they reset.
- API accounts with keys on the machine: DeepSeek, OpenRouter, Anthropic, OpenCode Go.
- Local models: whether the server answers.

Each card shows which tasks and agents used it most. You get notifications at 80%, 95% and when a limit runs out. Keys stay on the machine. The drawer's machine meter shows the tightest limit.

**Logins.** Which agent CLIs are signed in (Claude Code, Codex, pi, opencode, Gemini CLI) and when each login runs out. Sign in again, paste a key, or sign out from the phone. A key found in a shell profile gets a warning.

**Spaces and panes.** Each space lists its panes: an agent and its state, or a shell and its folder. Tap a pane to open its chat or terminal. On classic herdr, a long press renames, moves, swaps, zooms or closes panes, tabs and workspaces.

On the Herdr desktop app's spaces (herdr-core), you can start and close terminals but not change layouts:

- **⋯ → New → Terminal** on a space opens a shell in the space's folder, as a new tab.
- **Close terminal** (in an agent's or terminal's **⋯**, or a long press on a terminal on the machine screen) ends it, after asking.
- Spaces that belong to Shepherdr close the way Shepherdr closes them.

**Edit machine** (in **⋯**) changes its name, address, jump host, herdr session, board and **Allow host scripts**. Turn that off for a machine where the app should copy no helper scripts.

## Notifications

**While the app is closed.** Turn it on in **Settings → Notifications**. You need a [UnifiedPush](https://unifiedpush.org) app such as [ntfy](https://ntfy.sh). The app then sets up [herdr-push](../host/herdr-push/README.md) on each machine, which sends sealed pushes within seconds. The app also looks at its machines itself every 15 minutes, in case a push is lost.

Each notification has buttons: **Approve**, **Deny** or **Reply** for an agent, **Accept** or **Answer** for a task. A tap opens the chat. A notification goes away once it is answered anywhere.

**What reaches the phone** has a switch for each kind:

- **Needs you**: an agent asks, a machine runs low.
- **Finished**: an agent ended its turn. Off by default.
- **Went wrong**: a review or check failed.
- **Limits**: an AI provider near or past a limit.

Also there:

- **Hold while I'm at the machine**: notifications are silent while you type in herdr or a machine's screen is unlocked.
- **Spaces**: the spaces with their own Notify, each with **Reset**.
- Inside the app, a short banner drops from the top when another agent starts asking.

**Live Updates** show each working agent in the notifications, with a **Stop** button. On Android 16 they sit at the top of the shade with a chip in the status bar.

**Quiet hours** hold what can wait (finished agents, Live Updates) during Do Not Disturb, Bedtime, Driving, car mode, or a schedule you set. Questions still come through. When quiet hours end, what was held comes as one summary.

**On Windows**, herdr-push runs in your signed-in session without administrator rights. The PC has to be awake and signed in.

## The brief

The brief says what happened since you last looked, on every machine: what merged, what waits for you, which agents finished, and what is still running. Each line opens its task or chat. **Last 24 hours** is there too.

Open it in **Settings → Brief → Open the brief**.

- **When the brief comes**: get it as a notification at up to four times a day, on the days you pick.
- **Read aloud**: the phone reads it over your music, also with the screen off. **Read it aloud as it comes** reads the scheduled brief by itself, in the car or always.

## From outside the app

- **Share** a link, text or screenshot to **Herdr Assistant** from any app. It lands in the Assistant's message box for you to send.
- **Send to the Assistant** is in the menu of selected text, next to Copy.
- **Quick Settings tile**: **Assistant** opens the chat and says what needs you.
- **Launcher shortcuts** (long press the icon): Needs you, the Assistant, Start work, and your two latest chats.
- **Widgets**: **Ask the Assistant**; **Herdr** with counts across machines; **Needs you** with each item's buttons on its row.
- **Floating chats**: **⋯ → Float over other apps** in an agent chat floats it as an Android bubble. The first time, allow **All conversations can bubble**.

## Change the app from the phone

The app can change itself, with the work done on one of your machines.

1. Long-press a screen's title (or **⋯ → Change this screen**), say what should change, and **Send**. It goes, with a screenshot, to the **herdr-android** space's Assistant.
2. The machine makes it a task. You review and accept it as any task.
3. Once merged, the app offers **Update ready**. **Install** hands it to Android, which asks you first.
4. **Undo** reverts the last change as a new task.

**Settings → Change the app** picks the build machine and says what it is missing. It needs a herdr-android checkout, the Android SDK, JDK 17 or newer, Python 3, git, and Shepherdr 3 with a **herdr-android** space. To build updates signed with the release key, keep the key in herdr-secret ([`docs/RELEASING.md`](RELEASING.md#building-updates-on-a-machine)); without it, each update is CI's release for that merge.

## Settings and security

**Fingerprint and app lock** asks for your fingerprint (or face, or PIN) before what can't be undone: **Always allow** on a question, **Approve and merge**, **Stop a process**, **Agent logins** and **Replace the SSH key**. Each can be turned off.

**App lock** asks when the app opens, and again after it was away for a while. It hides the app in recent apps, and a locked phone's notifications only say "Something needs you".

**SSH key**: copy the public key, **Back up** to a passphrase-protected file, **Restore or import**, or make a **New key**. The app never replaces a key without asking.

Settings also has notifications, the brief, Autopilot, text sizes, the Assistant's voice (a voice server of your own, else the phone's voice), quick replies and Change the app.

## Big screens and accessibility

On a window 840 dp wide or more (a tablet, an open foldable, desktop windowing) the drawer stays open as a sidebar. Rotating or resizing keeps what is open.

- TalkBack gets every long-press action as an action on the row: **Answer yes**, **Answer no**, **Reply…**, **Open terminal**, **Why this state?** and more.
- Chats and the terminal have their own text size, on top of the phone's (**Settings → Text size**).
- Back is predictive, and answering or sending gives a light haptic.
