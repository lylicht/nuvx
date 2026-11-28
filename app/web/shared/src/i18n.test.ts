import { describe, expect, it } from 'vitest';
import { faTranslations, resolveVyralLanguage, translationFor } from './i18n';

describe('shared Persian localization', () => {
  it.each(['fa', 'fa-IR', 'fa_Arab'])('recognizes system language %s', (locale) => {
    expect(resolveVyralLanguage([locale])).toBe('fa');
  });

  it('falls back to English for unsupported system languages', () => {
    expect(resolveVyralLanguage(['fr-CA', 'de-DE'])).toBe('en');
  });

  it('uses the first supported preference in the system language list', () => {
    expect(resolveVyralLanguage(['en-US', 'fa-IR'])).toBe('en');
    expect(resolveVyralLanguage(['fr-CA', 'fa-IR'])).toBe('fa');
  });

  it('translates known interface copy and safely preserves unknown metadata', () => {
    expect(translationFor('fa', 'Settings')).toBe('تنظیمات');
    expect(translationFor('fa', 'Mitski')).toBe('Mitski');
    expect(Object.keys(faTranslations).length).toBeGreaterThan(60);
  });
});
