# Tuner architecture

An Android tuner that listens through the phone microphone. Its distinguishing
feature is the **smart filter**: once it knows which note you are tuning to, it
stops treating the input as "any sound" and starts treating it as "a string
that should be close to this note", which lets it filter and measure far more
aggressively.

- [1. Shape of the system](#1-shape-of-the-system)
- [2. Module map](#2-module-map)
- [3. Signal path](#3-signal-path)
- [4. The smart filter](#4-the-smart-filter)
- [5. Automatic switching](#5-automatic-switching)
- [6. Tunables](#6-tunables)
- [7. What has been verified](#7-what-has-been-verified)
- [8. Limits and next steps](#8-limits-and-next-steps)

## 1. Shape of the system

A native Android app in Kotlin, in two Gradle modules:

- **`:engine`** — the tuner itself: DSP, instrument knowledge, and the
  search/lock logic. Pure Kotlin with no Android dependency, so the whole of
  it is unit-tested on the JVM. Its API is one call:
  `TunerEngine.process(block) → TunerReading`.
- **`:app`** — the Android shell: microphone in, Jetpack Compose UI out.

Two threads, each with one job:

```mermaid
flowchart LR
  mic([Phone microphone]) --> rec[AudioRecord<br/>UNPROCESSED source, float PCM]
  subgraph audio [tuner-audio thread]
    rec --> pipe[tunerReadings flow<br/>reads 1024-sample blocks]
    pipe --> eng[TunerEngine<br/>all DSP + lock logic]
  end
  subgraph main [Main thread]
    vm[TunerViewModel<br/>StateFlow of TunerUiState] --> ui[TunerScreen<br/>note, needle, strings]
  end
  eng -- "TunerReading (≈47 per second)" --> vm
  vm -- "TunerConfig (StateFlow)" --> pipe
```

- The **audio thread** blocks on `AudioRecord.read`, runs the engine on each
  block, and emits the reading. The engine is single-threaded by design and
  lives entirely here.
- The **main thread** only holds state and draws. Readings are queued for it,
  and if it ever falls more than about a second behind, the oldest are
  dropped: a slow frame on the UI side never makes analysis wait.
- Configuration travels the other way as an immutable `TunerConfig` in a
  `StateFlow`; the audio loop compares it between blocks. No locks anywhere.

The pipeline is a cold `Flow` (`tunerReadings` in `TunerPipeline.kt`).
Collecting it opens the microphone; cancelling the collection closes it. That
makes the lifecycle hard to get wrong: the view model starts a collection on
"Start" and cancels it on "Stop" or when the app leaves the screen.

### Getting clean audio out of a phone

Phones process microphone audio for speech by default, and that processing is
poison for a tuner: noise suppression regards a sustained string as background
noise and removes it, and automatic gain control pumps the level.
`MicrophoneSource` therefore:

- records from `MediaRecorder.AudioSource.UNPROCESSED` when the device
  supports it, otherwise from `VOICE_RECOGNITION` (the least-processed source
  every device has to provide);
- explicitly switches off any `NoiseSuppressor`, `AutomaticGainControl` or
  `AcousticEchoCanceler` the device still attaches to the recording session;
- reads float PCM at 48 kHz (falling back to 44.1 kHz), mono.

This control over the input is the main reason the app is native rather than a
web page: browsers only *request* that processing be turned off.

### What the screen shows

- **The meter**: a ±50 cent scale with a needle, coloured by how close the
  string is.
  The scale stands still while the string is within 25 cents of its note.
  Further out it follows the string on a curve (a little at first, more the
  further out), so that the needle keeps moving outwards instead of resting
  against the end; it slides only as far as leaves the in-tune zone in sight
  at the other end, and for a string further out than that (about 90 cents)
  it shows more cents instead. The waterfall moves with it.
- **The waterfall** under it: the last eight seconds on the same horizontal
  scale, newest at the top and moving down, in one of two styles chosen in the
  settings.
  - *Spectrogram* (the default): each row is the string's spectrum with the
    noise floor removed, drawn as light on black. Colour says where on the
    scale it is (green in tune, through yellow, to red far off) and brightness
    says how much energy is there. A clean note lights a narrow stripe, a
    stiff or dying one a wider and fainter one, a second string a second
    stripe, and silence is black. The tuner's reading runs through it as a
    thin white line. For contrast, brightness is shaped in two parts: within a
    row it rises exponentially towards the row's peak (half the peak's level
    is drawn at 8 %), so a clear peak is a clear line and the wide faint
    skirts around it do not haze over the scale; and quiet rows are lifted, so
    a note that has faded to a tenth of full level still shows.
  - *Line*: only the reading, in the colour the needle had at each moment.

  Either way, something running straight down is a steady pitch and something
  slanted is a pitch that is changing, so the player sees how far a turn of
  the peg moved the string and whether a note drifts as it rings. The picture
  belongs to one string and starts over when the tuner moves to another.
- **The note name**: large while the tuner is chromatic (it is then the main
  information), small once the instrument is known, because the row of string
  buttons already highlights which string is sounding.

The top right corner has two uses. While the tuner runs it is the smart-filter
switch; while it is stopped it is the button that opens the settings, since
neither is wanted in the other state.

A second screen, **Settings**, holds the waterfall style, the needle response
(see "A steady needle") and the list of tunings: every standard tuning with a switch that puts it in or out of use,
and a *Duplicate* button that makes an editable copy (name, the note of each
string, more or fewer strings). Only the tunings in use appear in the
instrument picker and are candidates for automatic recognition. Changes apply
at once, also to a running tuner, and are saved.

## 2. Module map

```
engine/src/main/kotlin/io/github/alfonslm/tuner/
  engine/
    TunerEngine.kt          orchestration + search/locked state machine
    StabilityWindow.kt      "has one note been sounding steadily?"
    ReadingSmoother.kt      median + exponential smoothing, in-tune detection
    RingBuffer.kt           recent audio history
    TunerConfig.kt          every tunable, with defaults
    TunerReading.kt         what the engine reports per block
  dsp/
    Fft.kt                  radix-2 FFT
    Spectral.kt             windowed power spectrum → filtered autocorrelation
    WideDetector.kt         note-agnostic pitch detector (acquisition, watchdog)
    HarmonicComb.kt         string model: comb filter + harmonic validation
    SpectralHistory.kt      what was sounding before the last pluck, to take it away
    PartialPitch.kt         the locked reading: every partial measured on its own, stiffness learned
    FocusedDetector.kt      target-aware pitch detector (tracking)
    FoldedSpectrum.kt       one row of the spectrogram: partials folded onto a cents scale
  music/
    Notes.kt                Hz ↔ MIDI ↔ names, cents
    Instruments.kt          the standard tunings: strings + harmonic model each
    InstrumentIdentifier.kt automatic mode: which instrument do these strings belong to?
    Targets.kt              TargetSet: the notes the tuner snaps to
engine/src/test/            synthetic plucked strings, DSP and engine scenarios

app/src/main/kotlin/io/github/alfonslm/tuner/app/
  audio/AudioSource.kt      interface: something that delivers audio blocks
  audio/MicrophoneSource.kt AudioRecord implementation (see above)
  TunerPipeline.kt          AudioSource + config → Flow<TunerReading>
  TunerViewModel.kt         UI state, start/stop, settings changes
  PitchTrace.kt             the last 8 s of spectrogram rows and readings
  Tunings.kt                the user's tuning list: switch, duplicate, edit, save
  Settings.kt               persisted choices (SharedPreferences)
  MainActivity.kt           permission, lifecycle, keep-screen-on
  ui/TunerScreen.kt         the tuner screen
  ui/SettingsScreen.kt      waterfall style and the tuning list
  ui/Meter.kt               the cents scale and needle
  ui/Waterfall.kt           the history under the meter, as spectrogram or line
  ui/Theme.kt               colours
app/src/test/               the pipeline driven by a synthetic microphone
```

Dependencies point one way: `ui → view model → pipeline → engine → dsp, music`.
The app reaches the microphone only through the `AudioSource` interface, which
is what lets the tests replace it with a generated signal.

## 3. Signal path

Every block (1024 samples, ≈21 ms) the engine does this:

```mermaid
flowchart TD
  blk[audio block] --> ring[RingBuffer]
  ring --> wsp[Wide analyzer<br/>Hann · FFT → power spectrum]
  wsp --> wd[WideDetector<br/>band-pass · autocorrelation · peak pick]
  wd --> stab[StabilityWindow<br/>same target for 180 ms?]
  ring --> fsp[Focused analyzer<br/>window sized for the locked note]
  fsp --> comb[Harmonic comb around target]
  comb --> fd[FocusedDetector<br/>constrained autocorrelation peak]
  fsp --> val[Harmonic validation<br/>on the unfiltered spectrum]
  fd --> val
  wd --> sm{{Mode logic}}
  stab --> sm
  val --> sm
  sm --> smooth[ReadingSmoother] --> out[TunerReading]
  sm -. "target + comb width" .-> comb
```

Both detectors share one idea, implemented in `dsp/Spectral.kt`:

> pitch = position of the autocorrelation peak, where the autocorrelation is
> computed as `IFFT(power spectrum × weights)`.

The **weights are the filter**. The wide detector uses a band-pass over the
instrument's range. The focused detector uses a harmonic comb. Filtering in
the power-spectrum domain costs one multiplication per bin, is zero-phase, and
can be redesigned every frame for free — which is what lets the comb follow
the string as it is tuned.

Two details make the result accurate to a fraction of a cent:

- the frame is shorter than the FFT, so the autocorrelation never wraps around;
- the result is divided by the window's own autocorrelation, otherwise the
  taper drags every peak towards shorter lags (several cents sharp on low notes).

## 4. The smart filter

### What we know before measuring

A string produces partials at near-integer multiples of its fundamental,
stretched slightly by stiffness:

```
f_k = k · f0 · √(1 + B·k²)        k = 1, 2, 3 …
```

`B` (inharmonicity) and the number of useful partials come from the instrument
preset (`HarmonicModel`). And when tuning, `f0` is close to a known target.
So of the whole spectrum, only narrow regions around `k · f_target` can contain
the string. Everything else is, by definition, something else.

### The comb

`buildComb()` creates one pass band per partial:

```
gain
 1 ┤   ┌──┐
   │   │  │        ┌──┐                       band k is centred on f_k(target)
 ¼ ┤   │  │        │  │        ┌──┐           flat over ± widthCents, cosine edges
   │   │  │        │  │        │  │    …      weighted 1/k²
 0 ┼───┴──┴────────┴──┴────────┴──┴─────► Hz
       f1          f2          f3
```

- **Flat top of ± `widthCents`**: a string that is that far off passes
  undistorted, so the filter itself never biases the reading.
- **1/k² weighting**: in an autocorrelation, a partial pulls on the peak
  position in proportion to its power × k². Undoing that keeps the stretched
  upper partials of a stiff string from dragging the reading sharp.
- Bands are padded by the analysis window's main-lobe width; narrower than
  that is physically meaningless for a frame of that length.

### What the lock buys

| Knowing the target lets us… | Effect |
| --- | --- |
| remove everything between the partials | hum, room noise, speech and other strings never reach the estimator |
| search only lags near the target period | octave errors — the classic tuner failure — become impossible |
| size the window for this one note (120 to 150 ms) | fine enough to keep the partials of *other* strings out of each pass band |
| narrow the comb as the string approaches | maximum selectivity exactly when the last few cents matter |

### Reading the pitch off the partials

The autocorrelation peak says which note this is and roughly where. It is one
number for the whole sound, and the loudest partials decide it. On a string
instrument those are the lowest ones, and they are the least trustworthy:

- **Other open strings ring along** at exactly the partials they share with
  the plucked one. On a guitar the third partial of the A string and the
  fourth of the low E both lie on the high E's fundamental; an equal-tempered
  fifth puts the A's 2 cents above it, the low strings' stiffness adds to
  that, and so does any amount they are out of tune. They are driven through
  the bridge, come up over a fifth of a second and outlast the plucked note.
- **A string is stiff**: partial k sounds at k·f0·√(1 + B·k²), progressively
  sharp, and how much weight the upper partials get moves the reading.

A screenshot from a phone showed both: the needle a few cents sharp of the
spectrogram's stripe and wandering twice a second, the stripe steady. The
simulation of that (a stiff high E, every partial a beating pair, the A and
low E strings ringing along 8 cents sharp) read +2.1 cents on average and
wandered from -0.3 to +4.2.

Recordings of a real guitar then showed it directly. The high E string was 7
cents flat, and partials 4 to 7 said so steadily. The fundamental did not: it
swung between -10 and +10 cents, 1.3 times a second, because a second tone sat
on it at 0 cents, the in-tune A and low E strings ringing along. A second
after the pluck the string's own fundamental had faded and only that tone was
left, so the tuner showed a flat string as in tune.

So the locked reading is taken from the partials one at a time
(`PartialPitch`). Each one's frequency is the peak of the spectrum next to
where it is expected, interpolated to a small fraction of a bin; with the
stiffness taken out, each is an estimate of the fundamental. They are combined
so that a partial counts for more the higher it is (the same error in Hz is k
times fewer cents) and the stronger it is, and so that the ones that disagree
are left out: a weighted median finds what most of the weight agrees on, and
only the partials within about 2 cents of it are averaged. The same
simulation then reads +0.5 and stays between +0.1 and +0.9.

A partial that disagrees is also remembered. While at least three partials
agree, one that does not is being pulled by something else, and that
something outlasts the plucked string. After five frames of disagreeing it is
left out for the rest of the note, also once the others have faded and there
is no majority left to outvote it; trust returns slowly if it agrees again,
and at once at the next pluck. When only distrusted partials are left, the
string can no longer be read: the estimate is marked **faded**, nothing is
shown, and the lock waits for the next pluck instead of falling back on the
wide-band reading, which would be that same ringing tone.

The stiffness B belongs to the string, not to the moment, so it is measured as
the note sounds: the slope of the partials' offsets against k², as the median
of the slopes between pairs of partials, followed slowly and remembered per
string. The preset's value is only the starting point; the first clean frame
replaces it. Frames in which the partials do not lie on a stiff string's line
(an attack, two notes in one window) teach nothing. The spectrogram lays the
partials over each other with the same measured stiffness, so the stripe and
the needle agree; with a fixed value the stripe sat a cent to one side on any
string that differed from the preset.

### Which octave

A small microphone hears little of a low string's fundamental; the second
partial is easily 10 dB above it. Three things follow, all seen on the
recordings (a laptop microphone, low E and A strings, fretted notes on them):

- The wide detector takes the shortest period that is nearly as good as the
  best one. "Nearly" was 65 % of the best, chosen when a low ratio was the
  only way to hear a plucked string over one ringing below it. Since
  `SpectralHistory` does that job, the ratio is 85 %, and the note is found at
  its own octave from the start.
- Once locked, the wide detector naming an overtone of the locked string moves
  the lock only if the string's other partials hold under 30 % of the energy
  *and* there is a string within 60 cents of that overtone. A guitar's low E
  went to "D string, 198 cents sharp" without the second condition.
- When the comb loses the string for a moment (a pluck's attack does that)
  while the wide detector names an overtone and the string's other partials
  are still present, the lock waits 400 ms before believing it.

### Keeping it honest

A comb makes *any* input look periodic at the target, so every focused
estimate is checked against the **unfiltered** spectrum (`harmonicProfile()`).

The check must not ask "is this the only thing sounding?". While tuning it
never is: the strings plucked a moment ago are still ringing. It asks four
narrower questions, and a reading is valid if all four are answered well:

| Check | Question | Rejects |
| --- | --- | --- |
| `prominence` | Do the partials stand out of the noise floor? (Energy on them, in multiples of what the floor alone would put there.) | silence, noise, a note that has died away |
| `explained` | Is the string the main thing in its band? (At least 45 % of the tonal energy.) | a string that has faded below the others |
| `fundamentalSupport` | Is there energy on partials that are *not* multiples of 2, 3, 5, 7? | a note an octave (or twelfth…) *above* the target, all of whose partials fit the comb, e.g. E4 while locked on E2 |
| `subharmonic` | Is there less energy *between* the partials, where those of a note 2, 3 or 4 times lower would lie, than on them? | a lower string whose overtone lands on the target, e.g. E2 while locked on E4 |

The last limit is lenient (as much energy between the partials as on them),
because that is also where the partials of other ringing strings fall. A
pinned string uses a stricter one, since nothing else then guards it.

### Strings that are still ringing

On a guitar the low E and the A string are exact sub-octaves of the high E:
82.4 × 4 and 110 × 3 are both 330 Hz. Pluck the high E while either still
rings and the combined sound repeats at the *lower* string's period. In a
single frame that is indistinguishable from the lower string alone with weak
overtones. An analysis of one frame at a time has to guess, and whichever way
it leans, it is wrong for one of the two.

What tells them apart is time: a ringing string only fades, the plucked one
appears. `SpectralHistory` uses that.

- It keeps a short run of recent spectra (half a second). The share of the
  current spectrum that stands well above anything they held is the
  **novelty**; it is near zero for anything steady or fading, noise included,
  and jumps when a note starts. A jump is a pluck. (Above anything, not above
  their average: two tones a few cents apart beat, and the swell after a quiet
  moment of the beat is several times the average before it. Taken for a
  pluck, it blanked the needle and started it over.)
- At a pluck, the least each bin has held over that run becomes **the
  background**: what was sounding before.
- From then on both detectors look first at the spectrum with the background
  taken away. The strings that were already ringing are gone from it; the new
  one is nearly intact (in the tests: under 5 % of the old string left, over
  95 % of the new one kept). Only if that gives no clear note do they fall
  back to the whole sound.
- The background fades out over about 1.5 s. The strings it stands for are
  dying away too, and taking away more than is really still there would eat
  into the new string's partials where the two lie close.

The focused detector, which is created after the pluck that led to its lock,
gets its background from the last second of audio still in the buffer.

One case is excluded on purpose. When the string that is *already locked* is
plucked again, the background would be that same string at the pitch it had
before the peg was turned. Taking that away cuts into every partial of the new
note from one side and shifts the reading (by 3 cents in the test that caught
it). So a pluck of the locked string drops the background, and the whole sound
is measured.

Two further things make a mixture easier:

- **Window length.** Every focused window is at least 120 ms, which resolves
  17 Hz. Shorter windows had been used for high strings, but what crowds a
  high string is not its own partials: the second partial of a D string lies
  36 Hz below a high E, and inside a 50 ms window's reach it pulled the
  reading flat by 6 cents at a level 12 dB below the E.
- **The wide detector's choice of period.** It takes the shortest period that
  is at least 65 % as good as the best, not 90 %, so a quieter string an
  octave or two below does not win merely because its period fits both.

### The spectrogram

The waterfall needs a spectrum on the meter's scale: ±50 cents around the
target (rows are computed to ±100, a semitone, so that the scale can slide
towards a string that is far out). Taken naively that is hopeless. At 82 Hz, ±50 cents is ±2.4 Hz, far
below what any window of practical length resolves; the row would be one
smear. `FoldedSpectrum` gets a usable picture out of ordinary spectrum
analysis with two moves:

- **Fold the partials.** Every partial carries the same pitch information, and
  partial k is k times sharper on a cents scale, because the window's
  resolution is fixed in Hz while a cent at partial k is k times as many Hz.
  The neighbourhoods of all partials are mapped onto one cents axis and added.
  A partial counts for more the sharper it is (k²) and the stronger it is (its
  amplitude relative to the strongest), so the picture follows the frequencies
  that carry the sound and a faint overtone cannot pull it aside.
- **Use a long window.** 48 periods of the target (capped at 0.6 s) instead of
  the detectors' 16. A history display can afford the delay: each row is dated
  at the middle of its window, about a quarter of a second back, which is a
  few pixels of an eight-second waterfall. The needle is not affected.

The **noise floor is removed per partial**: the floor is the median power in
that partial's own neighbourhood, and only power above three times that
counts, so hum and rumble near one partial do not lift the floor anywhere
else. A partial is drawn only once its peak stands 10 dB clear. The row as a
whole is scaled by how far the strongest partial is above the noise, so a note
that fades away fades from the display.

With all eight partials present the stripe is about 9 cents wide. It is wider
when the sound gives less to go on: only low partials left, overtones that
disagree, a pitch that moved within the window. Nothing in the row is derived
from the pitch estimate; the stripe and the needle are two independent views
of the same sound. On a string much stiffer than its preset assumes they can
sit a few cents apart, because the stripe is dominated by the upper partials
and the reading deliberately is not.

A row is produced every third block (about 16 per second) and travels in
`TunerReading.spectrum`.

A row also says whether the tuner had a reading for the audio it describes
(`SpectrumRow.read`). Rows without one are something other than the string: a
pluck's attack, the string being stopped by the hand, other strings ringing on
after it has faded. On a recording those showed as bright patches away from
the stripe, one of them a semitone above a string that had just been damped,
at a hundredth of its level; each row is drawn relative to its own peak, which
is what makes a fading note stay visible and also what lifted these. They are
drawn at 30 % brightness, so the bright stripe is the string and nothing else.

### A steady needle

A real string does not give a steady estimate. Another string's overtone
sounding a few cents away makes it beat, two or three times a second, by
several cents either way. `ReadingSmoother` steadies the needle against that:

- a median of three drops a single stray frame;
- an exponential moving average with a time constant of 200 ms
  (`display.smoothingMs`) averages the wobble out;
- at a pluck the average starts over, so the needle goes to the new note at
  once instead of sliding there from the old value. The first readings of a
  note are averaged with equal weight until there are enough of them for the
  moving average to take over.

The price is that the needle trails a peg that is being turned by those
200 ms: 6 cents at 30 cents a second. How to trade the two is a matter of
taste and of the instrument, so the settings offer three responses: **Fast**
(100 ms), **Normal** (200 ms, the default) and **Steady** (400 ms).

An average that adapts (slow near the needle, fast far from it) was tried
first. It was no steadier against beating, since the beat swings further than
any zone narrow enough to be useful, and it followed a moving pitch only a
little better (6 cents behind where the plain average is 8), for four more
settings. The plain average stayed.

What the average hides is shown next to it. `TunerReading.spreadCents` is the
standard deviation of the estimates over the last 400 ms, measured on how far
each lands from the needle, less the recent mean of that distance (so a peg
being turned, with the needle steadily behind, adds little: about 2 cents at
30 cents a second).

The needle is an average, and an average is slow by design. Whether it can be
trusted is a different question and should not wait for it: when somebody
else starts to play, the reading should look doubtful at once, not drift for
half a second first. Two things are therefore reported with every reading,
both taken from the last frame or two:

- **the scatter** above, which rises to an estimate that lies further out
  than it within two frames and falls back over 400 ms. It is also kept
  apart for the estimates that fall below the others and those that fall
  above (`spreadFlatCents`, `spreadSharpCents`; equal to each other and to
  the overall figure when the scatter is even), because what disturbs a note
  usually pulls one way: something sounding just above it, say;
- **`confidence`**: how much of everything that is sounding is the note being
  read (`stringShare`: the energy on the string's partials as a share of all
  the energy from 60 Hz up to its last partial, less what would lie there by
  chance). It is taken on the whole sound, also when the pitch was measured
  with the ringing strings taken away. One string alone is near 1; a second
  note as loud brings it to about a half, whether higher or lower, a ringing
  string or another instrument. On the recordings a sustained note scores
  0.95 to 1.0, and the high E's dying tail 0.75.

The meter turns the worse of the two into how the needle looks (`unclarity`:
clear up to 1 cent of scatter and down to 92 % share, fully unclear from 6
cents or 55 %). The needle is three soft things laid over each other. A glow,
fading from its middle towards all its edges, is as wide as the scatter, to
each side by as much as the estimates stray to that side, and brighter on the
side with more of it: scatter that is all sharp gives a glow that leans sharp.
(Company widens it to at least a few cents on both sides alike; that says the
reading is doubtful, not which way.) A bulge in it, four
times narrower, is where the reading is. A line through the middle is only as
crisp as the note is clear. The less clear, the fainter the line and the
shorter the peak, which then runs out into the low, wide glow. One clean note
is a tall sharp needle; a weak, noisy or crowded one a short soft peak in a
glow. The look follows a change for the worse in 50 ms and a change for the
better in 300, so it warns quickly and does not flicker.

Nothing is shown for 150 ms after a pluck is noticed, nor in a frame that is
10 dB louder than the quietest of the three before it: that is the pluck
arriving, a frame before there is enough of it to be recognised, and it reads
anything (20 cents off on the recordings). The attack is noise and
a moment of wildly sharp pitch, and the analysis windows still hold part of
the sound from before; the previous reading stays on screen meanwhile.

### One time axis

Results come out later than the sound they describe, and by different
amounts. A reading describes the middle of its analysis window (60 to 75 ms
back) and is then averaged over 200 ms, so the needle shows the pitch of about
0.3 s ago. A spectrogram row describes the middle of a much longer window:
about 0.25 s.

Drawn at the time they were produced, the reading would run beside the stripe
whenever the pitch moves, not through it. So both carry the time of their
audio: a row in `SpectrumRow.time`, a reading as `TunerReading.delay` behind
`time`. The waterfall places everything at audio time, and its top edge is the
moment the newest reading describes, which is what the needle is showing.

## 5. Automatic switching

Two automatic modes plus a manual pin. The wide detector runs in both automatic modes: as the source of readings while
searching, as a watchdog while locked.

```mermaid
flowchart LR
  S(["Search<br/>wide-band readings"])
  L(["Locked<br/>focused readings"])
  P(["Pinned<br/>focused, fixed target"])
  S -- "one note steady for stableMs" --> L
  L -- "nothing usable for holdMs" --> S
  S -- "tap a string" --> P
  L -- "tap a string" --> P
  P -- "tap it again" --> S
```

While locked, the target can change without leaving the mode: the lock moves
when another note becomes steady, and hands off to the neighbouring target
when the string is tuned past the midpoint between them.

**Search → Locked.** `StabilityWindow` watches the wide detector. When every
frame in the last `stableMs` (180 ms) is clearly pitched, maps to the same
target, and wanders less than `maxSpreadCents`, that target is "the correct
note": the lock engages and the comb opens just wide enough to contain the
string's current offset. Until then the wide-band reading is shown, so the
display is never blank while the decision is being made.

**While locked**, per frame, in this order (`TunerEngine.trackLocked`):

1. Focused estimate valid → use it, unless the wide detector steadily names
   another string *and* that is a new note. Whether it is depends on how the
   two relate:
   - an **overtone** of the locked string: usually the wide detector's own
     octave error, and ignored; believed only if little energy is left on the
     locked string's other partials (the higher string has been plucked while
     this one rings on);
   - a note the locked string is **an overtone of**: with both ringing, their
     common period is the lower one's, so the wide detector says that;
     believed only if that string has just been plucked;
   - **unrelated**: believed.
2. Focused estimate invalid and the wide detector steadily on a different
   target → move the lock there at once (plucking the next string re-targets
   in about a third of a second).
3. Focused estimate invalid but the wide detector hears something inside the
   capture range → show the wide reading against the locked target (covers a
   string that jumped out of the narrowed comb).
4. Otherwise show nothing. The lock is *held* through silence so re-plucking
   the same string is tracked from the first frame; after `holdMs` (2.5 s)
   the tuner returns to Search.

**Comb width** (`adaptComb`): `|current offset| + marginCents`, bounded by the
target's capture range. It opens instantly, closes gradually, and re-opens
fully after a few frames without a reading.

**Capture range** (`TargetSet.captureCents`): half the distance to the nearest
neighbouring target plus a small overlap — ±80 cents in chromatic mode,
±230–280 cents between guitar strings. The overlap gives hysteresis for the
hand-off when a string is tuned past the midpoint, so the target never flaps.

**User control**

| Control | Config | Behaviour |
| --- | --- | --- |
| Smart filter switch | `smartFilter: AUTO \| OFF` | `AUTO` (default) switches as described. `OFF` stays wide-band always. |
| Tap a string | `pinnedTargetId` | Forces the lock onto that string with a ±400-cent capture range and ignores every other note. For strings that are far off (new strings) or noisy rooms. Tap again to release. |

| Instrument: Automatic | `instrumentId: "auto"` | The default. Works out the instrument from the strings played; see below. |

Every `TunerReading` carries `mode`, `source` (`WIDE` / `FOCUSED`) and the
lock's target and comb width, so the UI can show exactly what the filter is
doing.

### Recognising the instrument

The default instrument is **Automatic**: the player should not have to say
what they are holding, because the strings give it away. It chooses among the
tunings in use (`TunerConfig.tunings`), which the user controls in the
settings: by default one per instrument (guitar, bass, ukulele, violin, viola,
cello). The alternates that ship with the app (Drop D, half step down, DADGAD,
Open G, 7-string, 5-string bass, mandolin, banjo) start switched off, because
a tuning that shares most strings with another makes recognition wait for the
string that differs. Custom tunings are candidates like any other.

One string says little — G3 is on a guitar, a violin and a viola. Two
different strings almost always settle it, because instruments differ both in
register and in the interval between neighbouring strings (fourths on a guitar
or bass, fifths in the violin family).

1. Until the instrument is known, the engine behaves as a chromatic tuner
   whose range spans the tunings in use (E1 to C6 by default). It is fully usable in this
   state: notes are shown and locked onto as semitones.
2. Each time a note settles (the same trigger that engages the lock), its
   pitch goes to `InstrumentIdentifier`. That keeps the two most recent
   *distinct* pitches and asks of every preset: could these be two different
   strings of yours, each within 150 cents of where it belongs? The fit is the
   RMS distance in cents.
3. If exactly one instrument fits, or the best fits at least 40 cents better
   than the runner-up, it is chosen. The engine rebuilds itself around that
   instrument's strings and harmonic model, and the lock carries over to the
   string that is still sounding. `TunerReading.instrumentId` reports the
   change; the UI shows the instrument and its string buttons appear.

From then on the tuner has what chromatic mode lacks: it knows a D string a
whole tone flat is a flat D string, not an in-tune C.

It is deliberately reluctant. A pair that fits two instruments stays undecided
until a string only one of them has is played (violin and viola share G3, D4
and A4). Only the last two pitches count, so one misheard note is forgotten as
soon as two good ones follow. A choice, once made, holds until the tuner is
stopped or the user picks an instrument; picking Automatic again starts over.

## 6. Tunables

All in `engine/TunerConfig.kt`, as defaults of an immutable data class. The
ones that shape behaviour:

| Setting | Default | Meaning |
| --- | --- | --- |
| `lock.stableMs` | 180 | how long a note must hold before the filter engages |
| `lock.maxSpreadCents` | 30 | how steady it must be during that time |
| `lock.holdMs` | 2500 | silence tolerated before unlocking |
| `focus.marginCents` | 35 | comb half-width when the string is in tune |
| `focus.minFrameMs` / `maxFrameMs` | 120 / 150 | focused analysis window |
| `focus.minProminence` / `minExplained` / `minFundamentalSupport` / `maxSubharmonic` | 12 / 0.45 / 0.06 / 1.0 | validation thresholds |
| `wide.minClarity` / `minLockClarity` | 0.75 / 0.5 | periodicity needed for a wide-band reading to be shown / to count towards a lock |
| `wide.peakPickRatio` | 0.85 | how readily a shorter period is preferred |
| `fresh.memoryMs` / `fadeMs` / `onsetRise` | 500 / 1500 / 0.25 | hearing a plucked string apart from ringing ones |
| `display.smoothingMs` | 200 | time constant of the needle's moving average; longer is steadier and trails a turning peg further. Set from the Needle response setting (100 / 200 / 400) |
| `display.spreadMs` | 400 | time over which the scatter shown behind the needle is measured |
| `display.attackHoldMs` | 150 | nothing is shown for this long after a pluck |
| `display.inTuneCents` / `inTuneHoldMs` | 3 / 400 | when the display turns green |

The standard tunings live in `music/Instruments.kt` (string notes + harmonic
model each); adding one there is one entry. The user's own are made in the
app and reach the engine the same way, as entries of `TunerConfig.tunings`.

## 7. What has been verified

`./gradlew test` needs no device. It runs:

- **75 engine tests** against a plucked-string synthesizer (inharmonic
  partials with individual decay, weak fundamental, white noise, mains hum):
  - each guitar string is identified, locked within 0.5 s of the pluck, and
    read to within 0.3 cent;
  - on a stiff string (B = 10⁻⁴) the wide detector reads about 3 cents sharp,
    the focused detector within 0.05 cent, and it measures the stiffness to
    within 5 %;
  - a guitar's high E as a microphone hears it (stiff, every partial a
    beating pair, the A and low E strings ringing along 8 cents sharp or flat)
    is read within half a cent on average, the needle moves by less than 1.5
    cents in three seconds, is never blanked by a beat, and the spectrogram's
    stripe is within 0.7 cent of it;
  - a neighbour 15 cents above the fundamental and nearly as loud does not
    move the reading by more than 0.3 cent;
  - a string 7 cents flat that fades under in-tune strings ringing along is
    read at its own pitch for as long as it is read, and then not at all;
  - a low E of which mostly the even partials are left stays the low E, where
    the partials alone would send the lock to the D string;
  - a pluck after the string was stopped by hand shows nothing until it reads
    the new pitch;
  - when another instrument comes in 10 dB below a ringing string, the string
    is still read to within 1.5 cents and the confidence falls below 0.92
    within 0.1 s; when it comes in as loud, whatever is shown has a confidence
    under 0.7;
  - in noise where wide-band detection fails on more than 15 of 20 trials, the
    focused detector is right on at least 19;
  - noise and silence never produce a reading; every other string of the
    instrument is rejected even with the comb fully open;
  - E2 ↔ E4, string changes, chromatic hand-off through a semitone, hold and
    release, and pinning behave as described above;
  - with the six strings plucked one after another and all left ringing, 2 s
    or 1.2 s apart, every string is followed for the whole of its time and
    read to within 3.5 cents (measured: within 1.5);
  - the high E is read to within 1.5 cents over a ringing low E, A or B at
    12, 6 and 3 dB below it, and the tuner goes to the low E when that is
    plucked over a ringing high E;
  - a pitch moving at 30 cents a second is trailed by the needle by 6 to 10
    cents, every reading is within a cent of the truth at the moment it says
    it describes, and the gap is closed within three quarters of a second of
    the pitch coming to rest;
  - with another string's overtone beating against the note 20 cents away, the
    needle swings by less than 60 % of what the estimate does;
  - a string plucked again after the peg was turned is read at its new pitch
    at once, to within half a cent, with nothing shown during the attack;
  - the spectrogram: a string shows as a stripe at most 11 cents wide within
    1 cent of where it is, on every string; noise, silence and mains hum leave
    the row empty; two strings 30 cents apart show as two stripes;
  - automatic mode: every pair of strings of every preset identifies its
    instrument (or stays undecided where two instruments share the pair), and
    the engine recognises guitar, bass, ukulele, violin, viola and cello from
    two plucked strings (a guitar within half a second of the second pluck);
    it chooses only among the tunings in use, tells standard from Drop D by
    the low string, reaches down to the B0 of a 5-string bass when that is in
    use, and recognises a custom tuning.
- **28 app tests**.
  - Nine drive the real pipeline (view model → flow → engine) with a synthetic
    microphone: a tone gets locked and shown, its spectrogram rows arrive
    without a gap, stopping closes the stream, a microphone that fails to open
    is reported, settings changes reach the running engine, settings persist,
    automatic mode recognises a guitar and offers its strings, a tuning
    switched off while selected falls back to automatic, a duplicated and
    edited tuning can be selected and tuned to (and retuned while running),
    and the line style switches the spectrogram analysis off.
  - Eight cover the tuning list: switching, duplicating, editing within
    limits, standard tunings being read-only, and saving and restoring
    (including damaged saved data).
  - Seven cover the waterfall's history, including that readings and rows
    are placed at the time of their audio.

`./gradlew :app:validateDebugScreenshotTest` renders the Compose screen on
the JVM in fifteen states (the tuner idle, searching, locked sharp, in tune,
with a held reading, pinned, chromatic with the filter off, with permission
denied, in three stages of automatic mode, in the line style, and fed by the
real engine on a synthesized string being tuned up; the settings screen as it
opens and with a custom tuning being edited) and compares them
with the reference images in `app/src/screenshotTestDebug/reference`. After an
intentional UI change, regenerate them with `:app:updateDebugScreenshotTest`.

Debug and release APKs build, and Android lint reports no issues.

What none of this covers is the part that needs hardware: `MicrophoneSource`
has been compiled but never run, and no audio from a real phone microphone has
been through the engine yet.

## 8. Limits and next steps

- **Checked against recordings, not yet on a phone by me.** Sixteen recordings
  of an acoustic guitar through a laptop microphone (the six open strings
  plucked several times each, and fretted notes from F2 to E5) were run
  through the engine with `RecordingReport`. They are not in the repository.
  After the changes they prompted, every open string is named correctly in
  guitar, automatic and chromatic mode; within a note 90 % of the readings lie
  in a band 1 to 3 cents wide (part of it the string's own drift as it
  decays); and there is no jump towards the strings ringing along and no
  octave change. The first moments after some hard plucks still read up to 10
  cents off, which may be the string (see below). A laptop microphone is not a
  phone's; the phone remains to be confirmed.
- **Known from the recordings and left as it is:**
  - a note whose pitch really moves is followed, and if it moves across the
    midpoint between two semitones in chromatic mode the target changes back
    and forth with it (one fretted note swung ±12 cents twice a second);
  - late in the tail of a low note, when its fundamental has all but gone, a
    chromatic lock can still move to the octave above (once in sixteen
    recordings, 7 s into the note);
  - in instrument mode a fretted note is shown as the nearest open string a
    long way out of tune, and beyond about three semitones above the top
    string an octave too low. The instrument modes are for open strings;
    chromatic is for everything else;
  - mains hum in a silent room can be read as a note (100 Hz showed as
    "A2 -182" at -62 dBFS for a moment).
- **A hard pluck really is sharp at first.** A string plucked hard starts
  above its pitch and settles over about a second; the tuner shows that, as it
  should, and the averaged needle runs a little behind it on the way down.
  Tune to where it settles, or pluck gently.
- **A string out of tune with itself.** The two planes a string vibrates in
  differ by a fraction of a hertz, which at a high E's fundamental is a cent
  or two. The reading sits between them, nearer the stronger one, and moves a
  little as their balance changes. That is the string, not the tuner.
- **Two strings equally loud remain ambiguous.** When a ringing string is as
  loud as the one just plucked, and for longer than about 1.5 s after the
  pluck, the tuner may go to the wrong one of two harmonically related
  strings. Plucking again settles it.
- **Not yet run on a phone by me.** Thresholds were set on synthetic signals and
  will need a pass with a real instrument; the first things to check are
  `levelGateDb` and `wide.minClarity` against real phone microphones, and how
  much CPU the engine takes per block on a mid-range device.
- **Tonal interference near a partial** (strong mains hum at 100/150 Hz next
  to low E, another instrument playing) biases any estimator. The validation
  rejects such frames rather than show a wrong value, so the needle may drop
  out instead.
- **The spectrogram costs an extra FFT** of up to 32768 points sixteen times a
  second. That is about 1 ms each on a desktop JVM; whether a slow phone keeps
  up is one of the things to measure on a device. `spectrogram.everyBlocks`
  and `spectrogram.periods` trade smoothness and sharpness for CPU.
- **Inharmonicity is assumed, not measured.** Fitting `B` per string from the
  observed partials would remove the remaining ≈0.5 cent bias.
- **Automatic mode goes by absolute pitch.** An instrument tuned a full
  semitone away from the tunings in use is ambiguous by pitch alone (E♭2 and
  A♭2 fit a bass's D2 and G2 as well as a guitar's E2 and A2), so it is left
  undecided and the tuner simply stays chromatic. Switching the matching
  tuning on, or making it, resolves that.
- **A custom tuning keeps the overtone model of the tuning it was copied
  from.** Only its name, notes and number of strings can be edited.
- **Automatic mode reacts a little later to the first string** than a fixed
  instrument does: until it knows the instrument it has to analyse windows
  long enough for a bass.
- **Chromatic mode starts at C2**; for a bass use Automatic or the bass
  preset, which size the analysis windows for E1.
- Natural follow-ups: alternate tunings, a strobe-style display driven by
  per-partial phase, release signing and a Play Store listing.
