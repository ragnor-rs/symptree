import type { Lang } from '../i18n/strings'
import type { StringKey } from '../i18n/strings'

/** Format an ISO-8601 instant in the user's UI locale. Falls back to the raw value. */
export function formatInstant(iso: string, lang: Lang): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return new Intl.DateTimeFormat(lang === 'kk' ? 'kk-KZ' : 'ru-RU', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date)
}

// Server error codes that have a dedicated localized message; everything else
// renders as the generic error. Keys exist in both ru + kk catalogs.
const ERROR_KEYS: Record<string, StringKey> = {
  forbidden: 'error.forbidden',
  not_cancellable: 'error.not_cancellable',
  invalid_time: 'error.invalid_time',
  inverted_range: 'schedule.error.inverted_range',
  overlapping_rows: 'schedule.error.overlapping_rows',
  missing_dates: 'schedule.error.missing_dates',
}

/** Map a server/validation error code to a catalog key, defaulting to the generic message. */
export function errorKeyFor(code: string): StringKey {
  return ERROR_KEYS[code] ?? 'error.generic'
}
