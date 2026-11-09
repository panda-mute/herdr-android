# herdr-usage

What the AI providers a machine uses have left, read on that machine. Herdr for Android copies it there over SSH (as it first opens the machine's usage, and beside [herdr-push](../herdr-push/README.md)) and runs it, so each provider is asked with the logins and keys already on the machine: **no key or token ever comes to the phone**, only the readings. It changes nothing: it renews no login and writes only its own cache.

It is one Python 3 file (3.6 or newer), standard library only.

```sh
P=~/.local/share/herdr-usage/herdr-usage
python3 $P read            # everything, as one JSON line (what the app's usage page shows)
python3 $P read --fresh    # the same, asking the providers again now
python3 $P limits          # the limits alone (what herdr-push warns about every 5 minutes)
```

## Where each reading comes from

Each provider has one source that is the provider's own count; where there is none, the reading says it is an estimate.

| Provider | Source | Why this one |
| --- | --- | --- |
| **Claude Code** (subscription) | `GET https://api.anthropic.com/api/oauth/usage` with Claude Code's own login: `~/.claude/.credentials.json` (`claudeAiOauth.accessToken`; `$CLAUDE_CONFIG_DIR` too), or on macOS the keychain item `Claude Code-credentials`. Header `anthropic-beta: oauth-2025-04-20`. | It is what Claude Code's own `/usage` shows: the 5-hour session, the weekly limit and the per-model weekly ones (`limits[]`: `percent`, `resets_at`), live. Nothing in Claude Code's local files holds the percentages. The plan comes from the same file (`subscriptionType`, `rateLimitTier`: "Max 20x"). An expired token is **not** refreshed (that would rotate the login Claude Code holds): the card says it expired; Claude Code renews it the next time it runs. |
| **Codex** (subscription) | `GET https://chatgpt.com/backend-api/wham/usage` with Codex's login, `~/.codex/auth.json` (`tokens.access_token`, `ChatGPT-Account-Id: tokens.account_id`; `$CODEX_HOME` too). | The usage API behind Codex's `/status`: `rate_limit.primary_window` (5 hours) and `secondary_window` (a week), `used_percent` and `reset_at`, live. When it can't be asked (expired login, offline), the newest `rate_limits` Codex itself wrote into a session (`~/.codex/sessions/**/rollout-*.jsonl`, `token_count` events) are shown, with when they were written: those are only as fresh as Codex's last turn. |
| **DeepSeek** | `GET https://api.deepseek.com/user/balance` | DeepSeek's own balance. Spend today and this month are added from what this machine's agents recorded (pi's and omp's sessions carry each reply's cost). |
| **OpenRouter** | `GET https://openrouter.ai/api/v1/key` (and `/api/v1/credits`) | The key's own `usage_daily`, `usage_monthly`, its limit and what is left (a limit counts as a limit, with its reset); the account's credits where the key may read them. |
| **Anthropic API** (a key, not a login) | `GET /v1/organizations/cost_report` with `ANTHROPIC_ADMIN_KEY` | Only an admin key can read an account's costs. Without one the card shows what this machine's agents spent, from their session records, marked as this machine's. |
| **OpenCode Go** | `GET https://opencode.ai/zen/go/v1/models` checks the key; the limits are estimated | OpenCode Go has no usage API ([anomalyco/opencode#31084](https://github.com/anomalyco/opencode/issues/31084) asks for one; the script tries `/zen/go/v1/usage` first, in case it ships). Its limits are dollars of use in a rolling 5 hours, a week and a month ($12, $30, $60), so the estimate adds what this machine's agents spent on it in each window; use from other machines or the console isn't in it. An expired key says so. |
| **OpenCode Zen** | none | No balance API: what this machine's agents spent. |
| **Local models** (llama.cpp, Ollama, LM Studio, vLLM …) | pi's `~/.pi/agent/models.json`: a provider whose `baseUrl` is this machine or the local network | Shown as local, no limit; `GET <baseUrl>/models` says whether it answers and what it serves. |

Keys are found where the agents already keep them: the environment (`DEEPSEEK_API_KEY`, `OPENROUTER_API_KEY`, `ANTHROPIC_API_KEY`, `ANTHROPIC_ADMIN_KEY`, `OPENCODE_API_KEY`), pi's `~/.pi/agent/auth.json` (a key, an environment variable's name, or `!command` whose output is the key, as pi reads it) and opencode's `~/.local/share/opencode/auth.json`. pi's `anthropic` provider with a Claude login (an OAuth token) counts on the Claude Code card, since it spends the same subscription.

## What used it: tasks, agents, the week

Every provider card has a 7-day chart (tokens a day, and their cost where it is known) and the board tasks and agents that used it most. They come from the agents' own session records, read from where the last look stopped (they only grow), over the last week (and the month, for spend):

| Harness | Records | Counted |
| --- | --- | --- |
| Claude Code | `~/.claude/projects/*/*.jsonl` (and its subagents') | each reply's `usage` once (a reply is written once per content block, with the same message id) |
| Codex | `~/.codex/sessions/**/rollout-*.jsonl` | the growth of each session's `total_token_usage` |
| pi, omp | `~/.pi/agent/sessions/`, `~/.omp/agent/sessions/` | each reply's `usage`, with its provider and cost |
| opencode | `~/.local/share/opencode/opencode.db` (read only) | each assistant message's tokens and cost |

Tokens are input, output and cache writes; cache reads are left out (they would drown everything else, at a tenth of the price). A session belongs to the board task whose worktree it ran in (`~/.herdr/worktrees/<repo>/work-<n>-…` is `<repo>/<n>`, its title from the board's record `~/.herdr-projects/data/tasks/<repo>/<n>/task.json`), or to the Coordinator (`~/.herdr-projects/data/coordinator`). That is herdr-projects 1.x's layout, which herdr-usage still reads; the tasks of Shepherdr 3 and simple-herdr-projects (worktrees `task-<n>-…`) and their Assistants aren't told apart yet, so their sessions count under the agent only. An agent is its harness and model (`claude · claude-opus-5-5`).

The days are the machine's.

## The cache

`~/.cache/herdr-usage/` (`$XDG_CACHE_HOME`): `live.json` keeps each provider's answer for 4 minutes (`HERDR_USAGE_TTL`), so the app, its widget and herdr-push don't ask a usage API more than that (`--fresh` asks again, but not twice within 20 seconds); a provider that can't be asked keeps its last good answer, marked older. `scan.json` keeps how far each session file was read and what it added up to, so a look reads only what was written since (the first look on a busy machine takes a few seconds; one pass stops after 40 seconds, `HERDR_USAGE_BUDGET`, and the next goes on). `last.json` is the last full answer. All of it is mode 600 and holds no key.

The phone keeps the last answer per machine too, so the usage page, the top bar's glance and the agent choices show something the moment they open.

## Warnings

herdr-push runs `herdr-usage limits` every 5 minutes (`HERDR_PUSH_USAGE_EVERY`) and tells the phone when a limit passes 80% or 95%, or runs out, once per mark and window, and when that window is over (the notification comes down): it resets at another time, or its reset time has passed (a reading cached from before then counts as a fresh window). A rolling window without a reset time (OpenCode Go's estimate) is over once it is under 70%, so one hovering around 80% isn't told again and again. It also tells when the board's fallback hands a task to another agent because of a limit ("codex took over from claude: out of allowance until 17:00"), from the board's record of the task. The app's own looks while it is closed read the limits too, for a machine without herdr-push, and keep them as the phone's reading (with the limits a push carries), so the status widget follows them while the app is closed. Wherever the app shows a limit (the glance, the widget, the agent choices, the page), one whose reset time has passed since it was read counts as fresh, and a provider whose key or login failed shows that instead of what it would have left. All of these can wait: quiet hours hold them.
