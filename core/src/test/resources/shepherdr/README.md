Shepherdr 3.1.2's service answers, as the app reads them (`web/shared/types.ts` in Shepherdr's checkout):

- `health.json`, `snapshot.json`, `chats.json`: captured from a running 3.1.2 service (`/api/health`, the live link's
  first snapshot and two watched chats), then cut down and scrubbed: spaces, tasks, paths and the machine renamed,
  the account, plan limits and anything else personal taken out.
- `waiting.json`, `evidence.json`, `recipe.json`: the machine they were captured on had nothing waiting, no Ready task
  and no artifact, so these are written in the shapes `service/compat.ts`, `herdr-task show --evidence` and
  `core/recipe.js` produce, with made-up content.
