# Working on this repository

Android tuner. `engine/` is the tuner itself (pure Kotlin, tested on the JVM);
`app/` is the Android app. `docs/ARCHITECTURE.md` explains how and why it
works and is kept up to date with the code.

## Rule: check against the recordings before handing over a build

Before a build (an APK, a "this is done") is handed to the user, run the
engine over the recordings of a real guitar in `recordings/`:

```sh
./gradlew :engine:recordingsTest     # needs ffmpeg
```

and say in the reply that it was run and what it showed. This is in addition
to `./gradlew test`, which does not run it (the JVM cannot decode the files).

- It applies to any change that can alter what the engine outputs: DSP,
  detection, lock logic, smoothing, and every default in `TunerConfig`.
  UI-only changes are exempt, but say so instead of implying they were checked.
- If it fails, either the change is wrong or the engine now hears a real
  instrument differently on purpose. In the second case update the figures in
  `RecordingsTest` and give the reason in the commit message.
- When trying several values of a setting, check the value left in the code
  at the end before building. (A comparison loop once left `peakPickRatio` at
  0.65 while the documentation and the reported results were for 0.85, and
  two builds went out that way.)
- Synthetic tests passing is not evidence about a real instrument. Every fault
  found in the recordings was one the synthetic tests had passed.

To look closer at one recording (a timeline of readings, the partials over
time, a one-line summary per file) use `RecordingReport` in the engine's
tests; its header says how.

## Other habits of this repository

- `./gradlew test :app:validateDebugScreenshotTest :app:lintDebug` before a
  commit; after a deliberate UI change regenerate the reference images with
  `:app:updateDebugScreenshotTest` and look at them.
- New recordings from the user go into `recordings/` with a line in its
  README and expectations in `RecordingsTest`.
