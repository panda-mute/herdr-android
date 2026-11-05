# herdr-app-build

Builds the phone's update of Herdr for Android on the machine picked in **Settings › Change the app** (see the main README's "Changing the app from the phone"). One Python 3 file (3.6 or newer), standard library only. The app copies it to `~/.local/share/herdr-android/herdr-app-build` over SSH and runs `check` and `state` there; `build` is run by the herdr-android space's Assistant in its own Herdr terminal, because herdr-secret hands the release key only to an agent there.

```sh
P=~/.local/share/herdr-android/herdr-app-build
python3 $P check [--checkout DIR]   # what this machine has: checkout, origin, SDK, JDK, where the key is (one JSON line)
python3 $P build [--checkout DIR]   # build origin's main for the phone, or fetch CI's release of it
python3 $P state                    # the last build (one JSON line): what the phone reads
```

`build`:

1. fetches `origin main` in the checkout and takes its newest commit;
2. asks GitHub Actions for the run number of the Android workflow's push run of that commit (`gh api`, else the public API), waiting up to 15 minutes for it: that number is the version code CI gives the same merge;
3. with `HERDR_KEYSTORE_FILE`, `HERDR_KEYSTORE_PASSWORD` and `HERDR_KEY_ALIAS` in herdr-secret, runs `herdr-secret run … -- ./gradlew :app:assembleRelease` with `GITHUB_RUN_NUMBER` set, in a worktree of its own (`~/.cache/herdr-app-build/src`), so the checkout is untouched; herdr-secret masks the values in the build's log;
4. without the key (or when herdr-secret doesn't hand it over), waits up to 45 minutes for CI's release `v0.1.<run>` and downloads its `herdr-android.apk`;
5. keeps the APK and its size and SHA-256 in `~/.cache/herdr-app-build/` (`state.json`, `build.log`). Only one build runs at a time.

The phone copies the APK with `cat` over its SSH link, checks the size and SHA-256, then that Android reads it as this app, signed with the same key and newer, before it offers it.
