# Working on herdr-android

Kotlin and Jetpack Compose; see the README's **Layout** for the modules and [`docs/DESIGN.md`](docs/DESIGN.md) for the look.

## Checks

- `./gradlew -p core test`: core (SSH, herdr API, board, transcripts, terminal emulator), no Android SDK needed.
- `./gradlew :app:testDebugUnitTest`: the app's JVM tests; needs the Android SDK (`ANDROID_HOME`).
- `./gradlew -p tools/ui-preview render`: the screen previews, below.

## UI changes: check them with the screen previews, not an emulator

Every change to what the app shows is checked by rendering its screens on the desktop JVM, not by starting an Android emulator:

```sh
./gradlew -p tools/ui-preview render -Ponly=agents,chat  # just the ones the change touches, a few seconds each
./gradlew -p tools/ui-preview render                     # all scenes, dark and light, about half an hour
```

The PNGs land in `tools/ui-preview/build/shots/` (`<scene>.png` dark, `<scene>-light.png` light). They are the app's real Compose screens running against a fake herdr machine with fixed demo data, so the same code always gives the same pixels. How it works and how to add a scene: [`tools/ui-preview/README.md`](tools/ui-preview/README.md).

- **Render the scenes the change touches** with `-Ponly`; the whole set takes about half an hour (each shot about 3 to 4 s), so leave it to CI, which renders it on four runners at once in about 12 minutes, unless the change is shared by every screen.
- **Look at the shots** of every screen you changed, in both themes, before you call a UI change done.
- **Show them in review**: put the before and after shots of the changed screens in the pull request description (or your report). CI also renders every scene and uploads them as the `herdr-screens-<run>` artifact of the Android run.
- **A new screen, sheet or dialog gets a scene** in `tools/ui-preview/src/main/kotlin/preview/Scenes.kt`. A new state worth seeing (an error, an empty list) does too.
- **Don't start an emulator** to look at a screen. Emulators take 20–60 GB of RAM and are easily left running. If something truly needs a device (a system picker, notifications, the keyboard), say so and ask first; stop the emulator when done.
- If the preview doesn't compile after your change, you added an Android-only call to shared code: see "When it doesn't compile" in `tools/ui-preview/README.md`.
