# Tuner

An instrument tuner for the phone microphone, with a **smart filter**: after
it has heard a steady note it locks onto that string and filters the input
with a harmonic comb built for it, which keeps the reading stable and accurate
in noise and makes octave errors impossible.

Runs in the browser (TypeScript, Web Audio). See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how it works.

## Run

```sh
npm install
npm run dev        # http://localhost:5173
npm test           # DSP + engine tests on synthetic strings
npm run build      # static site in dist/
```

Browsers only give microphone access to secure pages: `localhost` works, and
to open it on a phone you need to serve it over https (for example deploy
`dist/` to any static host).

## Use

1. Pick the instrument, tap **Start tuner**, pluck a string.
2. The tuner shows the note wide-band, then locks ("Locked on A2 · harmonic
   filter ±35¢") once the note is steady. Pluck another string and it follows.
3. Tap a string button to pin the tuner to it (useful for a string that is far
   off, or in a noisy room). Tap again to release.
4. The **Smart filter** switch turns the automatic locking off.
