# Recordings

An acoustic guitar recorded through a laptop microphone (AAC, mono, 48 kHz).
They are what the engine is checked against before a build is handed to
anyone:

```sh
./gradlew :engine:recordingsTest     # needs ffmpeg on the PATH
```

`RecordingsTest` in the engine's tests holds what is expected of each file.
To look at one in detail, see `RecordingReport` there.

| File | What is played | What it showed |
| --- | --- | --- |
| `open-E4` | high E string, plucked four times, about 8 cents flat | the low strings ringing along on its fundamental, in tune, and outlasting it |
| `open-B3` | B string, four times, about 11 cents flat | the same, as the note fades |
| `open-G3` | G string, four times, 56 cents flat, stopped by hand between plucks | plucks that start 20 cents sharp of the string; the hand stopping it |
| `open-D3` | D string, four times, in tune | a clean case |
| `open-A2` | A string, five times, in tune | a low string heard mostly as its octave |
| `open-E2` | low E string, four times, in tune | the same |
| `fretted-01` to `-06` | single fretted notes: F2, F#2, G2, B2 then C3, E3, F3 | weak fundamentals; pitch moving with finger pressure |
| `fretted-07`, `-08` | notes between two semitones, with vibrato | the note name changing back and forth across the midpoint |
| `fretted-09` | a B, 43 cents sharp | |
| `fretted-10` | F4, G4, A4, B4, C#5, E5 up the top string | high notes; the slides between them |

The offsets are what the guitar happened to be tuned to on the day, read off
the upper partials with a long analysis window, not a reference.
