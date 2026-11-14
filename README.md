# Tuner

An instrument tuner for Android that listens through the phone microphone,
with a **smart filter**: after it has heard a steady note it locks onto that
string and filters the input with a harmonic comb built for it, which keeps
the reading stable and accurate in noise and makes octave errors impossible.

Native Kotlin, Jetpack Compose. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
for how it works.

<p>
  <img src="app/src/screenshotTestDebug/reference/io/github/alfonslm/tuner/app/ui/TunerScreenshotsKt/RealEngineOutput_fd9b1438_0.png" width="240" alt="An A string being tuned up, with its spectrogram">
  <img src="app/src/screenshotTestDebug/reference/io/github/alfonslm/tuner/app/ui/TunerScreenshotsKt/LockedInTune_fd9b1438_0.png" width="240" alt="G3 in tune">
</p>

## Build

Open the folder in Android Studio and run the `app` configuration, or from a
terminal with the Android SDK installed (`ANDROID_HOME` set, or `sdk.dir` in
`local.properties`):

```sh
./gradlew :app:installDebug   # build and install on a connected phone
./gradlew test                # engine + app unit tests (no device needed)
./gradlew :app:validateDebugScreenshotTest   # UI against the reference images
./gradlew :engine:recordingsTest             # the engine against recordings of a real guitar (needs ffmpeg)
```

Requires Android 8.0 (API 26) or newer.

The last of these is the check to run before a build goes to anyone: the
synthetic tests have passed every fault that the recordings in `recordings/`
later showed.

To see what the engine makes of a recording (no phone needed), convert it to
a mono float WAV and run the report; the notes heard, their offsets and how
steady the reading was are printed one line per file:

```sh
ffmpeg -i note.m4a -ac 1 -ar 48000 -c:a pcm_f32le recordings/note.wav
TUNER_SUMMARY=recordings ./gradlew :engine:test --rerun -i | grep "SUM "
```

`RecordingReport.kt` in the engine's tests describes two more detailed
reports.

| Module | What it is |
| --- | --- |
| `engine` | The tuner: DSP, instruments, search/lock logic. Pure Kotlin, tested on the JVM. |
| `app` | The Android app: microphone capture and the Compose UI. |

## Releases

A release is made from the Actions page: choose the `Release` workflow and "Run workflow" on
`main` (pushing a tag such as `v1.0.0-beta` does the same). It runs the checks, recordings
included, builds the APK and publishes it on GitHub. The version is `versionName` in
`app/build.gradle.kts`, the tag is `v` plus that, and a version with a hyphen becomes a
pre-release. The notes are taken from `docs/releases/<tag>.md` if that exists. On a pull request
the same workflow runs as a rehearsal without publishing and keeps the APK as an artifact.

Without further setup each release is signed with a one-off key, so it cannot be installed over
the one before. To sign them all with the same key, create a keystore once and add four
repository secrets (Settings, Secrets and variables, Actions): `TUNER_KEYSTORE_BASE64` (the
keystore file, base64-encoded), `TUNER_KEYSTORE_PASSWORD`, `TUNER_KEY_ALIAS` and
`TUNER_KEY_PASSWORD`.

## Use

1. Tap **Start tuner**, allow the microphone, and pluck a string. With the
   instrument on **Automatic** (the default) the tuner starts out chromatic
   and recognises the instrument once it has heard two different strings; you
   can also pick the instrument yourself.
   | Instrument | Two strings that set it |
   | --- | --- |
   | Guitar, bass, ukulele, cello | any two |
   | Violin | the E string and any other |
   | Viola | the C string and any other |

   (A violin and a viola share G, D and A, so those alone cannot tell them apart.)
2. The tuner shows the note wide-band, then locks ("Locked on A2 · harmonic
   filter ±35¢") once the note is steady. Pluck another string and it follows.
3. Tap a string button to pin the tuner to it (useful for a string that is far
   off, or in a noisy room). Tap again to release.
4. The **Smart filter** switch turns the automatic locking off.
5. While the tuner is stopped, the gear button (where the Smart filter switch
   is while it runs) opens **Settings**:
   - *Waterfall*: the history under the meter as a colour spectrogram or as a
     plain line.
   - *Needle response*: Fast, Normal or Steady, which is how long the needle
     averages the reading (0.1, 0.2 or 0.4 s).
   - *Tunings*: switch the standard tunings on and off (Automatic only chooses
     among those that are on), or **Duplicate** one and edit the copy's name,
     notes and number of strings to make your own.
