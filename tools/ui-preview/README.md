# Screen previews

Renders the app's real screens to PNGs on the desktop JVM, in dark and light, with no Android SDK and no emulator. This is how UI changes are checked and shown in review (see [AGENTS.md](../../AGENTS.md)).

```sh
./gradlew -p tools/ui-preview render                        # every scene → tools/ui-preview/build/shots/
./gradlew -p tools/ui-preview render -Ponly=agents,chat     # some scenes (both themes)
./gradlew -p tools/ui-preview render -Ponly=agents-light    # one shot
./gradlew -p tools/ui-preview render -Pout=/tmp/shots       # somewhere else
./gradlew -p tools/ui-preview render -Pshard=2/4            # every 4th scene from the 2nd, as CI does
```

Needs JDK 17 and, the first time, network for Gradle to fetch Compose Desktop. Every scene takes about 3 to 4 s per theme, so all of them take about half an hour (`-Ponly` renders just some); nothing runs after it exits. Each scene is saved as `<scene>.png` (dark) and `<scene>-light.png`, 824×1784 px (a 412×892 dp phone at 2×). The same code and data give the same pixels every run. A scene that throws prints `FAIL <name>` and the task fails. A shot that takes over 2 minutes is stuck (the slowest take about 25 s): it prints `HANG <name>` and what every thread was doing, and the task fails at once.

In CI the Android workflow's four `render (k/4)` jobs each render a quarter of the scenes (`-Pshard=k/4`), about 10 minutes apiece, and the `screens` job puts their shots together as the `herdr-screens-<run>` artifact; it fails when any of them did. Each job stops after 25 minutes at most.

## The README's screenshots

The README's gallery is these scenes, made smaller as WebP:

```sh
./gradlew -p tools/ui-preview render -Ponly=agents-v2,chat-tab,chat,chat-blocked,task-v2-ready,task-v2-pr,task-v2-browser,doc,machines,machine,terminal
cd tools/ui-preview/build/shots
# the tall ones, cut to their first screen into a copy
for s in agents-v2 agents-v2-light doc; do magick $s.png -gravity north -crop 824x1784+0+0 +repage $s-top.png; done
shot() { magick "$1.png" -strip -resize 540x -quality 82 "../../../../docs/screenshots/$2.webp"; }
shot agents-v2-top tasks; shot agents-v2-light-top tasks-light; shot chat-tab assistant; shot chat chat
shot chat-blocked blocked; shot task-v2-ready task; shot task-v2-pr pull-request; shot task-v2-browser browser
shot doc-top doc; shot machines spaces; shot machine machine; shot terminal terminal
```

## How it works

- `build.gradle.kts` compiles `app/src/main/kotlin` and `core/src/main/kotlin` as they are, for Compose Multiplatform on the desktop. The androidx libraries the app uses (navigation, lifecycle) have desktop builds under the same package names.
- A few files only work on Android or over real SSH. They are listed in `androidOnly` in `build.gradle.kts` and replaced by stand-ins with the same API in `src/main/kotlin/standins/`:
  - `HerdrApp.kt` (the Application) → `AppContainer` on a preview `Context`;
  - `data/IdentityStore.kt` (Android Keystore) → a fixed demo key;
  - `ui/terminal/TerminalView.kt` (an Android View) → the same grid drawn by the shared `TerminalPainter` on Skia;
  - `ui/common/Attach.kt` (ContentResolver, the camera, the photo picker) → no picker; a scene puts pictures in a composer with `images`;
  - `ui/common/Mic.kt` (Android's MediaRecorder and the microphone permission's dialog, for voice messages) → it records what a scene puts in `mic` (`preview/PreviewMic.kt`) at once: a small file as 7 seconds of speech, how loud, and the permission's answer; `ui/common/Playback.kt` (MediaPlayer) → a voice message played stands still at `mic`'s `playedTo`; `ui/common/Ears.kt` (talk to type: Android's speech recogniser, and the recording a machine writes down) → the phone's recogniser hears `mic`'s `heard` and writes down its `said` (none when `recognises = false`), and the machine's recording is a few bytes that `FakeShepherdr` writes down;
  - `ui/common/CodeScanner.kt` (Google Play services' code scanner, a system screen) → it reads the code a scene gives in `scanned` (`Demo.PAIR_LINK`, studio's `herdr-push pair` code); the SSH stand-in's `hostKey` answers for the addresses in `reachable` (`preview/PreviewScanner.kt`);
  - `ui/images/ImageCodec.kt` (Android's ImageDecoder and Bitmap.compress) → the same pictures read, made smaller and written with Skia;
  - `voice/AndroidVoiceCalls.kt` (the microphone and speaker of a voice call) is left out: the container stand-in gives `preview/PreviewVoiceCalls.kt`, the call a scene sets in `call`, with no sound;
  - `core/…/ssh/SshConnector.kt` → connections to the fake machines below;
  - `push/AndroidPush.kt`, `push/AndroidAlerts.kt`, `push/AndroidLive.kt` and `push/WorkService.kt` (UnifiedPush, Android's notifications and Live Updates, the service and alarms) are left out: the container stand-in gives the shared push code `preview/PreviewPush.kt` instead (ntfy installed, notifications and Live Updates allowed, nothing posted);
  - `MainActivity.kt` is left out; the scenes render `HerdrRoot` (`AppNav.kt`), as the activity does.
  - `share/ShareActivity.kt`, `share/CoordinatorTile.kt` and `share/Widgets.kt` (the share target, the Quick Settings tile and the home-screen widgets) are left out: what they open, the `share` and `ask` scenes show. The widgets are Android `RemoteViews`, which Compose on the desktop can't draw. `share/AndroidShortcuts.kt` (the launcher's shortcuts, through ShortcutManager) is left out too: the container stand-in gives `NoShortcuts`, and the `start` and `search` scenes show what they open.
  - `brief/AndroidBrief.kt` and `brief/AndroidVoice.kt` (the brief's notification and alarm, and text to speech) are left out: the container stand-in gives `PreviewBrief` (nothing posted, not in a car) and `PreviewVoice` (it keeps what would be read, and where, without a sound). The brief runs at the demo's fixed time (`Demo.NOW`, UTC), and devbox and gpu-box answer `herdr-push journal` with `Demo.devboxJournal()` and `Demo.gpuJournal()`.
  - `guard/AndroidUnlock.kt` and `guard/ConfirmActivity.kt` (androidx.biometric's prompt, and the window a guarded notification button opens) are left out: the container stand-in gives `PreviewUnlock`, which has a screen lock unless a scene says `screenLock = false` and answers yes at once (`unlockAnswer = null` leaves it up, for the `app-lock` scene). Android's prompt is a system window the harness can't draw; a phone without a screen lock shows the app's own confirmation (`prompt-confirm`).
  - `bubble/AndroidBubbles.kt` and `bubble/BubbleActivity.kt` (a chat floating as an Android bubble: its notification, shortcut and window) are left out: the container stand-in gives `PreviewBubbles` (bubbles allowed unless a scene says `bubblesAllowed = false`, nothing floats), and a scene with `bubble` renders the bubble's window (`BubbleRoot`) instead of the whole app.
- `src/main/kotlin/shims/` has the handful of `android.*` and Android-only androidx calls the shared code makes (`Context`, `SharedPreferences`, `Uri`, `LocalContext`, `BackHandler`, `AndroidView`, `Font(resId)`, `R.font`, `BuildConfig`). The app's own fonts come from `app/src/main/res/font`.
- `preview/FakeMachine.kt` is a herdr machine without SSH: it answers herdr's socket API (`session.snapshot`, `pane.read`, …), the shell commands the app runs (the herdr probe, `session list`, Shepherdr's service in `FakeShepherdr.kt`, the health probe in `FakeHealth.kt`), and the streams it opens (an agent's transcript, a terminal, the activity timeline's sessions). Everything above it, `HostSession`, `HerdrApi`, the Shepherdr client, the transcript readers, is the app's real code.
- `preview/FakeShepherdr.kt` is Shepherdr 3.1's service on a fake machine (`shepherdr` on a `FakeMachine`): `/api/health`, the live link's first snapshot and the chats as `bun` reads them there, `/api/action` (a message, an answer, the Accept card's evidence and Accept itself) and a recipe artifact's `recipe.json` and pictures (`preview/DemoArtifacts.kt`). Its demo board (`DemoV2`, `demo/shepherdr/`) is in 3.1's shapes, `web/shared/types.ts`; `DemoCore.shepherdr()` is devbox's herdr-core with Shepherdr 3's agents, labelled as it labels them.
- `preview/FakeShp.kt` is simple-herdr-projects on a fake machine (`shp list --json`, `shp show`, `shp accept`, `shp say`, a worker's terminal through `hc read`), with its demo board `DemoShp` (`shp-*` scenes). `preview/FakeGitHub.kt` answers `gh` for a task's pull request review (`DemoGitHub`, `task-v2-pr*`); `preview/DemoBrowser.kt` is Shepherdr's browser relay with a made-up page (`task-v2-browser*`); `preview/FakeDocs.kt` is a machine's `~/.herdr/docs` folder of herdr.doc documents (`doc*` scenes); `preview/FakeCore.kt` and `DemoCore.kt` are the Herdr desktop's herdr-core (`*-desktop` scenes); `preview/RealData.kt` has the shape of the owner's real work (eleven spaces, thirteen tasks, long titles), with the project names changed (`real-*` scenes).
- `preview/FakeCode.kt` is devbox's code: its repositories and checkouts, answering the code browser's commands (`CodeCommands`) with what they printed on a real repository: `code-demo/make.sh` builds a small made-up herdr-android repository with Git (task AND-9's worktree with two commits, a change not committed and a new file) and writes its files and those outputs to `src/main/resources/demo/code`. Run it again after changing `code-demo/files`.
- `preview/Demo.kt` is the data: the demo host the README's screenshots were once taken on (containers with made-up projects, since retired; the README's screenshots are now these scenes). devbox runs three agents (and Shepherdr 3, `DemoV2`, in the scenes that set it up); gpu-box and ci-runner are reached through it; mac-mini refuses the app's key. The transcripts and agent screens in `src/main/resources/demo/` were written by that demo host's `content.py` (still in the history as `8916b0f:docs/screenshots/demo-host/content.py`; the transcripts with their times, so the chat's turns read "Worked for 2m 36s" against `Demo.NOW`); `claude-app-more.jsonl` (what the app agent writes next: a failed test run, the fix, its answer, for `chat-done` and `chat-new-messages`) was written by hand in the same format. The activity timeline's data (`demo/activity/`: the app agent's session while it works, with times, for its chat's timeline) was written by hand, by a script, in its harness's own format.
- `preview/Main.kt` renders a scene: it saves the machines, starts the app's container, waits until every machine has answered, composes `HerdrRoot` in a 412×892 dp window, opens the scene's route, runs its actions, and saves the window with its dialogs and sheets.

## Adding a scene

Scenes are in `preview/Scenes.kt`, in render order:

```kotlin
Scene("board-v2", route = Routes.board(DEVBOX), setup = { v2() }),
Scene("task-v2-ready", route = Routes.board(DEVBOX, "AND-20"), setup = { v2() }),
Scene("agent-actions", actions = { longPressText("Speed up /jobs") }),
Scene("key-setup", key = IdentityStore.State.Missing),
Scene("chat-blocked", route = Routes.agent(DEVBOX, API_AGENT), setup = { host(DEVBOX).screens += API_AGENT to "…" }),
```

- `bubble` shows a chat as its floating bubble does (`BubbleRoot`), in place of the app (`bubble*` scenes).
- `widthDp` and `heightDp` set the window (412×892 dp by default); 840 dp wide or more shows the spaces sidebar beside the app (`wide*` scenes). `resize` changes the window after the actions, as a rotation or a resize does, and the shot is the new size (`rotate-*` scenes).
- `route` opens a screen by its `Routes` entry; `actions` taps what is on screen: `tapText`, `tapExact` (only that text, not a longer line that starts with it), `tapLabel` (an icon's content description), `longPressText`, `type`, `typeInto` (a form's field, by its label), and `settle()` to let the app catch up. Scrolling never settles in the desktop harness: a scene that needs what is further down sets a taller `heightDp`. `ctx.data.host(id)` changes a machine while the app runs, and `ctx.refresh(id)` makes the app read it again (see the `pill` scene).
- `talkback(text)` draws TalkBack's Actions menu for the element showing `text` (a card, a row): its custom actions as the app's semantics give them, with TalkBack's green outline round it; they are printed to the log too. `talkbackDo(text, label)` carries one of them out (`agent-why`). TalkBack itself is a system service the harness can't run; the menu is drawn in Android's look, not the app's.
- `drag(label, a to b, …)` puts a finger down on the element with that content description and moves it through the points (fractions of its size, or of a part of it given by `within`): marking up a picture in the editor (`picture-*` scenes).
- `hold(label, a to b, …)` is `drag` without letting go: the shot shows the finger still down (holding the mic, `chat-voice-*` scenes).
- `scrollTo(index)` scrolls the first list to an item at once (in a chat, which runs bottom up, 0 is the newest); with `ctx.data.host(id).append(session, lines)` the agent writes more to its transcript while the chat follows it, as `tail -F` gives it (`chat-new-messages`).
- `setup` changes the demo data before the app starts: `looked` (when each agent's chat was last on screen, `LookedStore`, by `<machine>/<pane>`: its "While you were away" card counts from it), `images` (pictures waiting in a chat's composer, by its DraftStore key; old screenshots of the app in `src/main/resources/demo/pictures` stand for the phone's, `Demo.picture`), `notes` and `codeView` (comments kept for later and how code is shown, `CodeStore`), `push` (the notification settings, `Demo.push()`), `brief` (the brief's schedule and when you last looked, `Demo.brief()`), `pushApps` and `notificationsAllowed` (the phone's side, `PreviewPhone`), `bubblesAllowed` (`PreviewBubbles`), `guard`, `screenLock` and `unlockAnswer` (the fingerprint's settings and the phone's side, `GuardStore` and `PreviewUnlock`), `composer` (messages held over the agents' composers and the quick replies, `ComposerStore`), `textSizes` (the chats' and the terminal's text sizes, `TextSizeStore`), `keyBar` (the terminal's key bar and Keep screen on, `KeyBarStore`), `selection` (what "Select text" selects in the terminal, in viewport cells, as a drag would: the harness can't drag across the grid), `scanned` and `reachable` (what the code scanner reads on Scan the code, and the host key each address shows; `pair-*` scenes), `machines`, `hosts` (each a `FakeMachine`: `snapshot`, `screens`, `transcripts`, `activity`, `records` and `said` (the timeline's sessions and board records), `board`, `refuse`, `health`, `journal`, `pictures` (pictures a chat reads back by their path there), `agentCommands` (what the composer's `/` list reads there; its `@` list reads the machine's `code`), or `on("method") { params -> result }` for one API call), `drafts`, `mic` (the microphone and what it hears, `PreviewMic.Setup`) and `voiceSent` (the messages sent as voice messages, `VoiceStore`).
- Keep the data fixed: no clocks, random ids or files from outside the repo, so a scene renders the same every time.

## When it doesn't compile

A new Android-only call in `app/` or `core/` (a new `android.*` class, Activity API or system service) breaks the preview's build. Either keep it in an Android-only file, add that file to `androidOnly` and give it a stand-in in `src/main/kotlin/standins/` with the same API, or add the missing call to `src/main/kotlin/shims/`. A change to a stand-in's original (say, a new method on `IdentityStore`) needs the same change in its stand-in.
