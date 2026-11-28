/**
 * Origins allowed to communicate with the VYRAL web application.
 * Production origins come from deployment configuration; local development
 * remains available by default.
 */
const DEV_ORIGIN = 'http://localhost:5173';

const configuredOrigins = (process.env.VYRAL_SITE_ORIGINS ?? '')
  .split(',')
  .map((origin) => origin.trim().replace(/\/+$/, ''))
  .filter(Boolean);

export const SITE_ORIGINS: string[] = configuredOrigins.length
  ? configuredOrigins
  : process.env.NODE_ENV === 'production'
    ? []
    : [DEV_ORIGIN];

export function matchPatternsFor(origins: string[]): string[] {
  return [...new Set(origins.map((origin) => {
    const url = new URL(origin);
    return `${url.protocol}//${url.hostname}/*`;
  }))];
}
