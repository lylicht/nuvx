import { useSyncExternalStore } from 'react';

const REDUCED_KEY = 'vyral:reduce-animations';
const systemMotion = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : null;
const listeners = new Set<() => void>();

function storedPreference(): boolean {
  return typeof localStorage !== 'undefined' && localStorage.getItem(REDUCED_KEY) === '1';
}

function publish(): void {
  if (typeof document !== 'undefined') document.documentElement.dataset.reduceAnimations = String(shouldReduceAnimations());
  for (const listener of listeners) listener();
}

export function reduceAnimationsEnabled(): boolean {
  return storedPreference();
}

export function shouldReduceAnimations(): boolean {
  return storedPreference() || (systemMotion?.matches ?? false);
}

export function setReduceAnimations(enabled: boolean): void {
  if (typeof localStorage !== 'undefined') {
    if (enabled) localStorage.setItem(REDUCED_KEY, '1');
    else localStorage.removeItem(REDUCED_KEY);
  }
  publish();
}

export function useReduceAnimations(): boolean {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    reduceAnimationsEnabled,
    () => false,
  );
}

systemMotion?.addEventListener('change', publish);
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event) => {
    if (event.key === REDUCED_KEY || event.key === null) publish();
  });
}
publish();
