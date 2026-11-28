import { resolveVyralLanguage, translationFor, type VyralLanguage } from '@vyral/shared/i18n';

export const extensionLanguage: VyralLanguage = resolveVyralLanguage([
  ...(navigator.languages ?? []),
  navigator.language,
]);

export function t(key: string): string {
  return translationFor(extensionLanguage, key);
}

/** Localize extension-owned static copy; track, artist, and provider names are left intact. */
export function localizeExtensionDocument(): void {
  const persian = extensionLanguage === 'fa';
  document.documentElement.lang = persian ? 'fa' : 'en';
  document.documentElement.dir = persian ? 'rtl' : 'ltr';
  for (const element of document.querySelectorAll<HTMLElement>('[data-i18n]')) {
    const key = element.dataset.i18n;
    if (key) element.textContent = t(key);
  }
  for (const element of document.querySelectorAll<HTMLElement>('[data-i18n-title]')) {
    const key = element.dataset.i18nTitle;
    if (key) element.title = t(key);
  }
}
