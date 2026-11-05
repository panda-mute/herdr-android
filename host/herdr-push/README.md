# herdr-push

A small watcher that runs on a machine with herdr and tells your phone when something there needs you, also while Herdr for Android is closed:

- an agent becomes **blocked** (it asks something: a permission, a question) or **finishes**; also the Herdr desktop app's agents, in herdr-core, read on its socket where a core runs (never starting one).

When that is over (answered on the desktop, the agent back at work), it says so too, and the phone takes the notification down.

It also looks at the machine itself every 30 seconds (`HERDR_PUSH_HEALTH_EVERY`, 0 for never): when RAM runs low (under 10% available) or a disk does (less than a tenth free, at most 20 GB), it tells the phone once, naming the three processes using the most memory, and again when there is room (15%, one and a half times the disk's mark). The readings of the last hour go to `health.json` for the app's chart.

It also warns about the **AI providers' limits** every 5 minutes (`HERDR_PUSH_USAGE_EVERY`, 0 for never), where the app put [herdr-usage](../herdr-usage/README.md) (`~/.local/share/herdr-usage/herdr-usage`, `HERDR_PUSH_USAGE` for another): when Claude Code's, Codex's or an API key's limit passes 80% or 95%, or runs out, it tells the phone once per mark and window, and again when that window is over (the notification comes down): a new reset time, its reset time passed, or, for a rolling window without one, under 70%. A tap opens the machine's usage page. Quiet hours hold it.

It also keeps a **journal** of the last week for the app's brief: each time an agent finishes or asks something, with the time and the agent as it was then (`journal.json`, at most 600 entries per herdr session; `HERDR_PUSH_JOURNAL_DAYS` changes the 8 days). The first look only learns how things stand, as for the pushes. The app reads it with `herdr-push journal --session <name> --since <seconds>`, which answers `{"ok": true, "now", "from", "upto", "entries": [...]}`: `from` is when the journal began (null before any look), `upto` its last look, so the app can tell a journal that stopped keeping track.

It can also follow agents a phone **watches** (`watch_panes` in its subscription): each change of a watched agent's status goes to that phone alone. The app's one-shot watches used it; it now asks for none (a space's Notify: All covers it), and subscribes again without them.

Every push also says whether someone is **at the machine** (`present`), so the phone can keep quiet then: how many seconds ago someone typed in a herdr client there (the last input of the terminal it runs in, as `w` reads it, or the last touch of `~/.local/state/herdr-push/present`, which anything may touch, a shell prompt say), and whether a desktop session of yours there is unlocked (logind's `LockedHint` and `IdleHint`, or a screen locker such as hyprlock or swaylock running; on macOS the console's lock), or null with no desktop. `herdr-push presence` answers the same, for the app's own looks.

For a phone with **Live Updates** on, it also sends what runs: the working agents, which the phone shows as Live Updates with a Stop button. That push goes out at once when one starts or stops, with their names and titles at most every 90 seconds (`HERDR_PUSH_LIVE_MINOR`), and again every 30 minutes while anything runs (`HERDR_PUSH_LIVE_KEEP`), so the phone knows the machine still watches. A phone without Live Updates gets none of these.

**Shepherdr 3's tasks** ("HERD-4 · Needs you", "HERD-4 · Finished") don't come from here: the app subscribes to the Shepherdr service's own push alerts, as its web pages do, with the same push address. herdr-push marks a task's agent with its task (`shepherdr_task` in the agent), so the phone then shows the task's alert alone, not the agent's too.

The phone shows each one as a notification with buttons: **Approve** and **Deny** for an agent's yes-or-no question, and **Reply** to the agent. A tap opens the agent's chat. The buttons work over the app's own SSH connection, never through the push.

It is one Python 3 file (`herdr-push`), standard library only, and needs nothing installed besides `python3` (3.6 or newer).

## Setting it up

The app does it for you:

1. On the phone, install a [UnifiedPush](https://unifiedpush.org) push app. [ntfy](https://ntfy.sh) is free and needs no account ([F-Droid](https://f-droid.org/packages/io.heckel.ntfy/), [Play](https://play.google.com/store/apps/details?id=io.heckel.ntfy)).
2. In Herdr, open **Settings → Notifications** and turn on **While the app is closed**. Allow notifications when Android asks. The app asks the push app for an address.
3. Every machine the app knows is set up as it connects: the app copies `herdr-push` there over SSH, subscribes the phone (its push address and a key made for that machine), and starts it so it also runs after a reboot. Settings shows each machine's state; **Test** sends a test notification from it. The switch next to a machine leaves it out (the app unsubscribes the phone there; with no phone left, herdr-push stops and removes its start-up entry).

A machine that is offline is set up the next time the app connects to it. Machines reached through a jump host work the same way.

The app also **looks at its machines itself** while it is closed (every 15 minutes by default; Settings → Notifications → The app also looks), with an exact alarm. That covers a machine without python3, or a push that got lost. Pushes arrive within seconds; the app's own looks only as often as you set.

## What it puts on the machine

| Path | What |
| --- | --- |
| `~/.local/share/herdr-push/herdr-push` | the watcher (the app replaces it when the app carries a newer version) |
| `~/.config/herdr-push/subscribers.json` | the subscriptions, one per phone and machine in the app (two machines in the app on this host, say two herdr sessions, are two): each one's push address, key and herdr session (mode 600) |
| `~/.local/state/herdr-push/` | `state.json` (what it saw last, so a restart doesn't repeat itself), `journal.json` (the last week of what agents did, for the brief), `health.json` (the last hour of RAM, swap and CPU readings), `herdr-push.log`, `run.lock` |
| `~/.config/systemd/user/herdr-push.service` | Linux with systemd: a user service, `Restart=always` |
| `~/Library/LaunchAgents/dev.herdr.push.plist` | macOS: a launchd agent |
| an `@reboot` line in your crontab | elsewhere, when there is `crontab` |

On Linux, a user service stops when your last login ends unless lingering is on. herdr-push turns it on (`loginctl enable-linger`) where the system lets you; where it doesn't, the app's Settings say "it stops when you log out", and an admin can run `loginctl enable-linger <you>`. Without any of these, the app starts herdr-push again whenever it connects.

## By hand

```sh
P=~/.local/share/herdr-push/herdr-push
python3 $P status          # running?, start-up entry, phones, last error
python3 $P once --dry-run  # one look: what it would send now (the first look only learns how things stand)
python3 $P test            # a test notification to every phone
python3 $P journal         # what it kept for the brief (--since <unix seconds>)
python3 $P presence        # whether someone is at this machine: typed (seconds ago) and unlocked
python3 $P pair            # a QR code a phone scans to add this machine (see Pairing a phone)
python3 $P ensure          # start it if it isn't running
python3 $P install         # start it now and with the machine
python3 $P uninstall       # stop it and remove the start-up entry (--purge: and the phones and its state)
journalctl --user -u herdr-push -f   # its log, under systemd (else ~/.local/state/herdr-push/herdr-push.log)
```

To install it without the app's help, copy `herdr-push` from this folder to `~/.local/share/herdr-push/` and run `python3 herdr-push install`. A phone still has to subscribe from the app (Settings → Notifications), because only the app has the phone's push address and makes the key.

What it watches comes from each phone's subscription: the herdr session (`default` unless the machine is set to another in the app), and the `herdr` the app found there. It reads it with `herdr api snapshot` and `herdr pane read <pane> --source visible` (for a blocked agent's question), every 4 seconds. `HERDR_PUSH_INTERVAL` (seconds) changes that; under systemd, set them with `systemctl --user edit herdr-push` (`[Service]` / `Environment=HERDR_PUSH_INTERVAL=10`).

Removing a machine from the app doesn't reach the machine. Turn it off in Settings → Notifications first, or run `herdr-push uninstall --purge` there.

## Pairing a phone

`herdr-push pair` adds a phone to this machine by a QR code, so nothing has to be copied from the phone into `authorized_keys`, and no address typed on a phone keyboard:

```sh
python3 ~/.local/share/herdr-push/herdr-push pair

# on a machine without it yet (or with one too old to know pair):
curl -fsSLO https://raw.githubusercontent.com/makefunstuff/herdr-android/HEAD/host/herdr-push/herdr-push
python3 herdr-push pair
```

It prints the code, what it names (the machine, its host key) and the link the code holds, to paste in the app when the phone can't scan. Then it waits until a phone paired, five minutes passed, or Ctrl-C. In the app: **Machines → Add machine → Scan the code**.

| Option | |
| --- | --- |
| `--host NAME[,NAME…]` | where the phone reaches this machine, tried before the ones found: the Tailscale name and address (`tailscale status`), then the address of the default route. At most four go in the code. |
| `--port N` | sshd's port (else the `Port` in `/etc/ssh/sshd_config`, else 22) |
| `--session NAME` | the herdr session the app opens there (else `default`) |
| `--name NAME` | the machine's name in the app (else the host name) |

How it works:

- It makes a one-time Ed25519 key with `ssh-keygen` and adds it to `~/.ssh/authorized_keys` as `restrict`, `expiry-time` five minutes on (OpenSSH 8.2 and newer), `command="… herdr-push pair-accept <token> …"`: that key gets no shell, no forwarding and no terminal, only `pair-accept`.
- The code holds `herdr://pair?v=1&n=<name>&u=<user>&a=<addresses>&p=<port>&k=<host keys>&c=<one-time code>&x=<expiry>`. The one-time code is that key's 32-byte seed; the host keys are the SHA256 fingerprints of `/etc/ssh/ssh_host_*_key.pub` (or of what `ssh-keyscan` sees on the port), both in URL-safe base64. The QR code is drawn by herdr-push itself, in the terminal's half blocks, dark on light.
- The phone tries each address and goes on only where the machine shows one of those host keys, so the code works with this machine and nothing that answers in its place. It logs in with the one-time key and sends its own public key. `pair-accept` checks it is one public key, adds it to `authorized_keys` (unless it is there already), takes the one-time key out and answers in one line of JSON. The phone then logs in with its own key to check it took, and pins the host key.
- The one-time key goes when it is used, when its five minutes are over, or when `pair` stops. A `pair` that was killed leaves it to the next `pair` or `pair-accept`, and to sshd's `expiry-time`. A code pairs one phone.

Anyone who sees the code within its five minutes could pair with it, as with any such code: show it where you'd type a password.

It touches one line in `~/.ssh/authorized_keys` while it waits (then the phone's key is there instead), and `pair-<token>.json` and `pair.lock` in `~/.local/state/herdr-push/`. It needs neither the watcher running nor a phone subscribed.

It can't help when sshd reads keys from elsewhere (`AuthorizedKeysFile` in `/etc/ssh/sshd_config`; the phone then says the machine still refuses its key), or when the phone reaches the machine only through another one (a jump host): add those by hand.

## Your own push server

ntfy uses ntfy.sh unless you point it elsewhere (ntfy → Settings → Default server). The push address the phone hands out is then on your server, and each machine needs to reach it over HTTP(S). Any UnifiedPush push app works.

## What is sent, and who can read it

Each push is a JSON message, compressed and sealed with the key the app made for that machine and gave it over SSH:

```
hp1.<base64url(nonce ‖ ciphertext ‖ tag)>
```

Two keys come from the shared one, `enc = HMAC-SHA256(key, "herdr-push enc")` and `mac = HMAC-SHA256(key, "herdr-push mac")`. The keystream is `HMAC-SHA256(enc, nonce ‖ counter)` for a 32-bit big-endian counter from 0, XORed with the zlib-compressed JSON, and `tag = HMAC-SHA256(mac, "hp1" ‖ nonce ‖ ciphertext)`: HMAC-SHA256 as a pseudorandom function in counter mode, then encrypt-then-MAC, because that is what Python's standard library has. The app (`core/…/push/PushSeal.kt`) checks the tag before anything else and ignores a push no machine's key opens, or one older than two days.

So the push server sees only that a machine sent the phone a few hundred bytes, and when. Someone who learns the phone's push address can't make it show anything, and a push sent again later is dropped (the app keeps the newest push time per machine). The notification buttons don't go back through the push: they connect to the machine over SSH like the app does. **Approve** and **Deny** read the agent's screen again first and press nothing if the question changed.

Inside, the message is:

```json
{"v": 1, "host": "devbox", "at": 1760000000, "present": {"typed": 35, "unlocked": true}, "events": [
  {"type": "agent", "agent": {"pane_id": "w1:p2", "agent_status": "blocked", "…": "…as session.snapshot gives it"},
   "where": "api › agents", "screen": "…the bottom of its screen…"},
  {"type": "test"},
  {"type": "health", "low": ["ram", "disk:/"], "memory": {"total": 65536000, "available": 3276800, "swap_total": 0, "swap_free": 0},
   "disks": [{"mount": "/", "device": "/dev/nvme0n1p2", "total": 500000000, "used": 495000000, "avail": 5000000}],
   "top": [["Android emulator · Pixel_8", 5872000], ["Gradle daemon", 3250000]]},
  {"type": "live", "agents": [{"agent": {"pane_id": "w1:p3", "agent_status": "working", "…": "…"}, "where": "api › agents", "workspace": "api", "since": 1759999000}]}
]}
```

A message stays under 4 KB sealed (what ntfy and most push servers take): several events go in separate pushes, a long screen is shortened, and a live event leaves out what doesn't fit (at most 10 agents). `since` is when the agent started working, on the machine's clock; the phone counts from the push's `at`.
