# How the app reaches a machine

Everything goes over one SSH connection per machine, with the key the app makes on the phone. Nothing runs on a server of ours. This page is for developers; what the user sees is in the [user guide](GUIDE.md).

## herdr

- **Classic herdr**: the app reaches herdr's socket API through `direct-streamlocal`, falling back to `herdr remote-api-bridge`. herdr answers one request per connection, so each call opens a new channel; `events.subscribe` keeps one open for live updates. Terminals use `herdr terminal session control`.
- **herdr-core** (the Herdr desktop app's runtime, also on Windows): one `herdr-core connect` channel per machine. How it is found and how its model maps onto the screens: [`HERDR_CORE.md`](HERDR_CORE.md).

A machine may run both; the machine screen shows both in one list.

## Boards

- **Shepherdr 3** (`core/…/shepherdr/`): the app forwards a port to the service on the machine's `127.0.0.1:7788` (or uses `curl` and `bun` there when the SSH server forwards nothing), with the token from `~/.herdr/shepherdr/token`. It reads what the 3.1 web app reads (`web/shared/types.ts`). The tests use 3.1.2's own answers, scrubbed (`core/src/test/resources/shepherdr/`).
- **simple-herdr-projects** (`core/…/shp/`): `shp list --json`, `shp show`, `shp accept --yes` and `shp say` over SSH, and the worker's terminal with `hc read`. No service, port or token; the app polls while a screen is open.

**Detect** picks `shp` where it is on the `PATH`, else Shepherdr 3.

## Chats and the activity timeline

An agent's chat and timeline are read from its own session files over SSH: Claude Code's (`~/.claude/projects`), Codex's (`~/.codex/sessions`), pi's and omp's (`~/.pi/agent/sessions`). Large sessions are pruned on the machine with Python 3 before they are sent, and only the newest part of a very long one is read.

Pictures and voice messages are copied to `~/.cache/herdr-android/uploads/` on the machine and named to the agent as `@path`, since herdr passes agents only text and keys.

## Code and pull requests

Code is read with `git` and the shell. Git runs with `GIT_OPTIONAL_LOCKS=0`, so reading changes nothing, not even the index. Pull requests are read and reviewed with the machine's `gh` (`core/…/github/`); what you write goes to `gh api` on its input, never on a command line.

## Docs

herdr.doc/2 documents are read from `~/.herdr/docs` with `ls`, `stat` and `head` (`core/…/docs/`). The list reads only each file's head; an open document is read again when its file changes.

## Host scripts

The app copies small Python 3 scripts to a machine when a feature needs them, unless **Allow host scripts** is off for it. Each is one file using only the standard library, with a `version:` line the app compares before replacing it.

- [`herdr-push`](../host/herdr-push/README.md): notifications, Live Updates, health readings, the brief's journal, and pairing.
- [`herdr-usage`](../host/herdr-usage/README.md): AI providers' usage and limits, with the keys and logins already on the machine. Nothing comes to the phone but the numbers.
- [`herdr-logins`](../host/herdr-logins/README.md): agent CLIs' logins, run with their own commands.
- [`herdr-app-build`](../host/herdr-app-build/README.md): builds the app's updates ([`RELEASING.md`](RELEASING.md#building-updates-on-a-machine)).
