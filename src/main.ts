import './ui/styles.css';
import { TunerController } from './app/TunerController';
import type { TunerConfig } from './engine/config';
import { getInstrument } from './music/instruments';
import { createTargetSet } from './music/targets';
import { TunerView } from './ui/view';

const STORAGE_KEY = 'tuner.settings';

function loadSettings(): Partial<Pick<TunerConfig, 'instrumentId' | 'a4' | 'smartFilter'>> {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}');
    getInstrument(saved.instrumentId); // throws if the saved preset no longer exists
    return { instrumentId: saved.instrumentId, a4: Number(saved.a4) || 440, smartFilter: saved.smartFilter === 'off' ? 'off' : 'auto' };
  } catch {
    return {};
  }
}

function saveSettings(config: TunerConfig): void {
  try {
    const { instrumentId, a4, smartFilter } = config;
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ instrumentId, a4, smartFilter }));
  } catch {
    // Private browsing: settings just do not persist.
  }
}

const controller = new TunerController(loadSettings());

const view = new TunerView({
  onToggleRunning: () => (controller.getStatus() === 'running' ? controller.stop() : controller.start()),
  onConfigure: (patch) => {
    controller.configure(patch);
    showConfig();
  },
});

function showConfig(): void {
  const config = controller.getConfig();
  const targets = createTargetSet(getInstrument(config.instrumentId), config.a4, {
    rangeMarginCents: config.wide.rangeMarginCents,
    captureOverlapCents: config.focus.captureOverlapCents,
    maxCaptureCents: config.focus.maxCaptureCents,
  });
  view.setConfig(config, targets.list);
  saveSettings(config);
}

let wakeLock: WakeLockSentinel | null = null;

controller.onReading = (reading) => view.setReading(reading);
controller.onStatus = (status, error) => {
  view.setStatus(status, error);
  if (status === 'running') {
    // Keep the screen on while tuning; unsupported browsers simply dim as usual.
    navigator.wakeLock?.request('screen').then((lock) => (wakeLock = lock)).catch(() => {});
  } else {
    wakeLock?.release().catch(() => {});
    wakeLock = null;
  }
};

// Release the microphone when the app goes to the background.
document.addEventListener('visibilitychange', () => {
  if (document.hidden && controller.getStatus() === 'running') void controller.stop();
});

showConfig();
view.setStatus('idle');
