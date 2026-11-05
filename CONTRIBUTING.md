# Contributing

Thanks for helping with Herdr for Android. Bug reports, ideas and pull requests are all welcome. This page says how to build and test it, how the code is written, and how to send a change.

## Building and running

You need **JDK 17**. The app also needs the **Android SDK** (platform 36, with `ANDROID_HOME` set or `sdk.dir` in `local.properties`); `core` and the screen previews don't.

```sh
./gradlew :app:assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug      # the same, installed on the phone adb sees
```

A debug build is `com.makefunstuff.herdr.debug`, so it installs next to the release app instead of over it, with its own machines and SSH key. `./gradlew :app:assembleRelease` builds the release APK, signed with the debug key unless you set the signing variables (see [Signing key](README.md#signing-key-once-for-the-repo-owner)).

### The host side

The app talks to [herdr](https://herdr.dev) over SSH, so you need a machine running herdr that your phone can reach by SSH: install herdr there and add the machine in the app ([Setting up a machine](README.md#setting-up-a-machine)). The host tools in `host/` (`herdr-push` for notifications, `herdr-usage` for the usage page and `herdr-logins` for the agent logins) are copied to a machine by the app when a feature needs them; each folder's README says how to run it by hand.

Without a machine of your own, [`docs/screenshots/demo-host`](docs/screenshots/demo-host) starts throwaway herdr hosts in containers with made-up projects, the ones the README's screenshots come from.

## Tests

```sh
./gradlew -p core test                               # core: SSH, herdr API, board, transcripts, terminal, host tools
./gradlew :app:testDebugUnitTest                     # the app's JVM tests (needs the Android SDK)
./gradlew -p tools/ui-preview render -Ponly=board    # screen previews of the screens you changed
```

The core tests run the host scripts themselves, so they want `python3` (and `sshd` and `ss` for a few); a test whose tool is missing is skipped, not failed. CI runs the core tests, builds the release APK and renders every screen on each push and pull request.

**A change to what the app shows is checked with the screen previews, not an emulator.** They render the app's real Compose screens on the desktop JVM against a fake herdr machine, in dark and light, to `tools/ui-preview/build/shots/`. Render the scenes your change touches, look at both themes, and put the before and after shots in the pull request. A new screen, sheet, dialog or state worth seeing gets a scene in `tools/ui-preview/src/main/kotlin/preview/Scenes.kt`. How it works: [`tools/ui-preview/README.md`](tools/ui-preview/README.md).

## Code style

- **Kotlin**, the official style (`kotlin.code.style=official`), four spaces. Write code that reads like the code around it: its naming, its idioms and its amount of comments.
- **Comments say what a thing is or why**, in plain sentences (`/** The newest push taken from it … */`), not what the next line does.
- **`core/` is plain Kotlin/JVM**: SSH, the herdr API, parsing and logic go there, with tests. `app/` is the Android app and its Compose screens.
- **The look lives in `ui/theme` and `ui/design`** ([`docs/DESIGN.md`](docs/DESIGN.md)). Screens use its colours, type, spacing and components and nothing else; a new one is added there first.
- **Android-only calls stay out of shared code** the previews compile; see "When it doesn't compile" in [`tools/ui-preview/README.md`](tools/ui-preview/README.md).
- **Host scripts** (`host/`) stay one file that needs nothing installed: `herdr-push`, `herdr-usage` and `herdr-logins` are Python 3.6+ with the standard library only. Raise a script's `version:` line near its top when you change it, so the app replaces older copies on machines.
- **Words on screen and in docs are plain and short.** A feature users see is described in the README.
- **The repo is public**: no real hostnames, IPs, user names, emails or keys in code, tests, demo data or screenshots. Use the demo machines (`devbox`, `gpu-box`, `ci-runner`, user `demo`).

## Issues

Open one on [GitHub Issues](https://github.com/makefunstuff/herdr-android/issues). For a bug, say:

- what you did, what you expected and what happened instead;
- the app's version (Settings, or the release tag), the phone and its Android version, and `herdr --version` on the machine;
- a screenshot or log if it helps, with your hostnames, addresses and keys taken out.

For a security problem, don't post the details: open an issue that says there is one and asks how to send them.

## Pull requests

1. Fork the repo and make a branch from the default branch.
2. Keep a pull request to one change, and its commit messages a short summary of what it does (`Voice messages in native chat`).
3. Run the core tests and, for a UI change, the previews of the screens it touches.
4. Open the pull request against the default branch: what it changes and why, how you checked it, and the before and after shots of any screen it changes.

CI must pass. Pull requests from forks build with the debug key, which is expected.

## AI agents

The repo is worked on with AI coding agents. [`AGENTS.md`](AGENTS.md) holds the instructions they read: which checks to run, and to check UI changes with the screen previews instead of an emulator. If you use an agent, point it there; you are still the one who reviews and stands behind what you send.

The owner runs their own work through [herdr-projects](https://github.com/eliasstravik/herdr-projects), a work board for herdr: that is where the task names (`AND-55`) and `work/<n>-…` branches on its pull requests come from. Outside contributors don't need it; a normal fork and pull request is all it takes.

## License

Herdr for Android is under the [MIT License](LICENSE). By sending a contribution you agree that it is licensed under the same terms. Code or assets from other projects need a licence that allows that, and a line in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
