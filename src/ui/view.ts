import type { TunerConfig, TunerConfigPatch } from '../engine/config';
import type { TunerReading } from '../engine/types';
import { INSTRUMENTS } from '../music/instruments';
import type { Target } from '../music/targets';
import type { TunerStatus } from '../app/TunerController';

export interface ViewHandlers {
  onToggleRunning(): void;
  onConfigure(patch: TunerConfigPatch): void;
}

const METER_RANGE_CENTS = 50;
/** How long the last reading stays on screen after the note stops. */
const HOLD_MS = 1500;

function element<T extends HTMLElement>(id: string): T {
  const found = document.getElementById(id);
  if (!found) throw new Error(`Missing element #${id}`);
  return found as T;
}

/**
 * Presentation only: renders the latest reading once per animation frame and
 * turns user input into config patches. It knows nothing about audio.
 */
export class TunerView {
  private readonly display = element<HTMLElement>('display');
  private readonly note = element<HTMLElement>('note');
  private readonly needle = element<HTMLElement>('needle');
  private readonly cents = element<HTMLElement>('cents');
  private readonly hertz = element<HTMLElement>('hertz');
  private readonly mode = element<HTMLElement>('mode');
  private readonly modeText = element<HTMLElement>('mode-text');
  private readonly strings = element<HTMLElement>('strings');
  private readonly startButton = element<HTMLButtonElement>('start');
  private readonly hint = element<HTMLElement>('hint');
  private readonly instrument = element<HTMLSelectElement>('instrument');
  private readonly a4 = element<HTMLInputElement>('a4');
  private readonly filter = element<HTMLInputElement>('filter');

  private latest: TunerReading | null = null;
  private lastVoiced: TunerReading | null = null;
  private lastVoicedAt = 0;
  private dirty = false;
  private status: TunerStatus = 'idle';
  private pinnedId: string | null = null;

  constructor(private readonly handlers: ViewHandlers) {
    for (const preset of INSTRUMENTS) this.instrument.add(new Option(preset.name, preset.id));
    this.instrument.addEventListener('change', () => handlers.onConfigure({ instrumentId: this.instrument.value }));
    this.a4.addEventListener('change', () => {
      const value = Math.min(466, Math.max(415, Math.round(Number(this.a4.value) || 440)));
      this.a4.value = String(value);
      handlers.onConfigure({ a4: value });
    });
    this.filter.addEventListener('change', () =>
      handlers.onConfigure({ smartFilter: this.filter.checked ? 'auto' : 'off' }),
    );
    this.startButton.addEventListener('click', () => handlers.onToggleRunning());

    const frame = () => {
      this.render();
      requestAnimationFrame(frame);
    };
    requestAnimationFrame(frame);
  }

  /** Reflects configuration (after start-up and after every change). */
  setConfig(config: TunerConfig, targets: Target[] | null): void {
    this.instrument.value = config.instrumentId;
    this.a4.value = String(config.a4);
    this.filter.checked = config.smartFilter === 'auto';
    this.pinnedId = config.pinnedTargetId;
    this.strings.replaceChildren(
      ...(targets ?? []).map((target) => {
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = target.label;
        button.dataset.id = target.id;
        button.setAttribute('aria-pressed', String(target.id === config.pinnedTargetId));
        button.title = 'Tap to keep the tuner on this string';
        button.addEventListener('click', () =>
          this.handlers.onConfigure({ pinnedTargetId: this.pinnedId === target.id ? null : target.id }),
        );
        return button;
      }),
    );
    this.latest = null;
    this.lastVoiced = null;
    this.dirty = true;
  }

  setStatus(status: TunerStatus, error?: unknown): void {
    this.status = status;
    this.startButton.dataset.running = String(status === 'running');
    this.startButton.disabled = status === 'starting';
    this.startButton.textContent = status === 'running' ? 'Stop' : status === 'starting' ? 'Starting…' : 'Start tuner';
    this.hint.textContent = status === 'error' ? describeError(error) : '';
    if (status !== 'running') {
      this.latest = null;
      this.lastVoiced = null;
    }
    this.dirty = true;
  }

  setReading(reading: TunerReading): void {
    this.latest = reading;
    if (reading.voiced) {
      this.lastVoiced = reading;
      this.lastVoicedAt = performance.now();
    }
    this.dirty = true;
  }

  private render(): void {
    const held = this.lastVoiced && performance.now() - this.lastVoicedAt < HOLD_MS ? this.lastVoiced : null;
    if (!this.dirty && !this.lastVoiced) return;
    this.dirty = false;
    if (!held) this.lastVoiced = null;

    const reading = this.latest;
    const live = reading?.voiced === true;
    const shown = live ? reading : held;
    const target = shown?.target ?? reading?.lock?.target ?? null;

    this.note.textContent = target ? target.label : '–';
    this.display.dataset.live = String(live);

    if (shown && shown.cents !== null && shown.frequency !== null) {
      const cents = shown.cents;
      const position = Math.max(-1, Math.min(1, cents / METER_RANGE_CENTS));
      this.needle.style.transform = `translateX(${(position * this.needle.parentElement!.clientWidth) / 2}px)`;
      const rounded = Math.round(cents);
      this.cents.textContent = `${rounded > 0 ? '+' : rounded < 0 ? '−' : ''}${Math.abs(rounded)} cents`;
      this.hertz.textContent = `${shown.frequency.toFixed(1)} Hz`;
      this.display.dataset.state = shown.inTune
        ? 'tuned'
        : Math.abs(cents) > 25
          ? 'far'
          : cents < 0
            ? 'flat'
            : 'sharp';
    } else {
      this.needle.style.transform = 'translateX(0)';
      this.cents.innerHTML = '&nbsp;';
      this.hertz.innerHTML = '&nbsp;';
      this.display.dataset.state = 'idle';
    }

    const activeId = target?.id ?? null;
    for (const button of this.strings.children as HTMLCollectionOf<HTMLElement>) {
      button.dataset.active = String(button.dataset.id === activeId);
    }

    if (this.status !== 'running') {
      this.mode.dataset.mode = 'idle';
      this.modeText.textContent = 'Tap start and pluck a string';
    } else if (reading?.lock) {
      const { lock } = reading;
      this.mode.dataset.mode = 'locked';
      const name = `${lock.pinned ? 'Pinned to' : 'Locked on'} ${lock.target.label}`;
      this.modeText.textContent = live
        ? `${name} · ${reading.source === 'focused' ? `harmonic filter ±${Math.round(lock.widthCents)}¢` : 'wide-band'}`
        : `${name} · waiting for the string`;
    } else {
      this.mode.dataset.mode = 'search';
      this.modeText.textContent = live ? (this.filter.checked ? 'Wide-band · waiting for a steady note' : 'Wide-band · smart filter off') : 'Listening…';
    }
  }
}

function describeError(error: unknown): string {
  const name = error instanceof DOMException ? error.name : '';
  if (name === 'NotAllowedError') return 'Microphone access was denied. Allow it in the browser settings and try again.';
  if (name === 'NotFoundError') return 'No microphone was found.';
  if (!window.isSecureContext) return 'The microphone needs a secure page (https or localhost).';
  return 'Could not start the microphone.';
}
