import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import { faTranslations, resolveVyralLanguage, type VyralLanguage } from '@vyral/shared/i18n';

const LANGUAGE_KEY = 'vyral:language';

function systemLanguage(): VyralLanguage {
  return resolveVyralLanguage(typeof navigator === 'undefined' ? [] : navigator.languages);
}

function validPreference(value: string | null): value is VyralLanguage {
  return value === 'fa' || value === 'en';
}

function syncDocument(language: string): void {
  const isPersian = language.toLowerCase().startsWith('fa');
  document.documentElement.lang = isPersian ? 'fa' : 'en';
  document.documentElement.dir = isPersian ? 'rtl' : 'ltr';
}

export function getLanguagePreference(): 'system' | VyralLanguage {
  const saved = localStorage.getItem(LANGUAGE_KEY);
  return validPreference(saved) ? saved : 'system';
}

export async function setLanguagePreference(preference: 'system' | VyralLanguage): Promise<void> {
  if (preference === 'system') localStorage.removeItem(LANGUAGE_KEY);
  else localStorage.setItem(LANGUAGE_KEY, preference);
  await i18n.changeLanguage(preference === 'system' ? systemLanguage() : preference);
}

export const i18nReady = i18n
  .use(initReactI18next)
  .init({
    resources: {
      en: { translation: {} },
      fa: { translation: faTranslations },
    },
    lng: getLanguagePreference() === 'system' ? systemLanguage() : getLanguagePreference(),
    fallbackLng: 'en',
    supportedLngs: ['en', 'fa'],
    interpolation: { escapeValue: false },
    react: { useSuspense: false },
  });

i18n.on('languageChanged', syncDocument);
if (i18n.isInitialized) syncDocument(i18n.language);
if (typeof navigator !== 'undefined') {
  window.addEventListener('languagechange', () => {
    if (getLanguagePreference() === 'system') void i18n.changeLanguage(systemLanguage());
  });
}

export { i18n };
