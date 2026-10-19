import { useMemo, useState, type FormEvent } from 'react'
import { useI18n } from '../i18n/I18nContext'
import { LANGS, type StringKey } from '../i18n/strings'
import type { ClinicSettings, ClinicSettingsUpdate } from '../api/types'

interface SettingsFormProps {
  initial: ClinicSettings
  /** Operators get a read-only view (no Save, disabled inputs) — FR-FXJEVE AC.11. */
  readOnly: boolean
  /** True while a save is in flight (disables Save). */
  busy?: boolean
  /** A server error code (e.g. `invalid_languages`) to surface localized; null when none. */
  errorCode?: string | null
  onSave: (req: ClinicSettingsUpdate) => void
}

// A short curated list of pilot-relevant zones offered as suggestions; the field
// still accepts any valid IANA string (the server validates — FR-FXJEVE Notes).
const TZ_SUGGESTIONS = ['UTC', 'Asia/Almaty', 'Asia/Aqtobe', 'Asia/Tbilisi', 'Europe/Moscow']

const DAYS_OF_WEEK = [0, 1, 2, 3, 4, 5, 6]

// Known server validation codes → catalog keys; anything else falls back to generic.
const ERROR_KEYS: Record<string, StringKey> = {
  invalid_timezone: 'settings.error.invalid_timezone',
  invalid_min_lead_hours: 'settings.error.invalid_min_lead_hours',
  invalid_first_day_of_week: 'settings.error.invalid_first_day_of_week',
  invalid_languages: 'settings.error.invalid_languages',
}

/**
 * Presentational clinic-settings form (FR-FXJEVE). Pure props in / `onSave` out —
 * the page wires it to the API + role (mirrors DoctorForm/DoctorsPage). Editing is
 * gated by [readOnly]; the server is the real authority (admin-only PUT).
 */
export function SettingsForm({ initial, readOnly, busy, errorCode, onSave }: SettingsFormProps) {
  const { t } = useI18n()
  const [timezone, setTimezone] = useState(initial.timezone)
  const [minLeadHours, setMinLeadHours] = useState(initial.min_lead_hours)
  const [firstDayOfWeek, setFirstDayOfWeek] = useState(initial.first_day_of_week)
  const [allowed, setAllowed] = useState<string[]>(initial.allowed_languages)
  const [defaultLanguage, setDefaultLanguage] = useState(initial.default_language)

  const timezoneChanged = timezone !== initial.timezone

  const errorMessage = useMemo(() => {
    if (!errorCode) return null
    const key = ERROR_KEYS[errorCode]
    return key ? t(key) : t('error.generic')
  }, [errorCode, t])

  function toggleAllowed(lang: string) {
    setAllowed((prev) => {
      const next = prev.includes(lang) ? prev.filter((l) => l !== lang) : [...prev, lang]
      // Keep the default a member of the allowed set (safe-edit invariant mirrors the server).
      if (!next.includes(defaultLanguage) && next.length > 0) setDefaultLanguage(next[0])
      return next
    })
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    onSave({
      timezone: timezone.trim(),
      min_lead_hours: minLeadHours,
      first_day_of_week: firstDayOfWeek,
      default_language: defaultLanguage,
      allowed_languages: allowed,
    })
  }

  return (
    <form className="settings-form" onSubmit={submit} aria-label={t('settings.title')}>
      {readOnly && <p className="hint">{t('settings.readonly_hint')}</p>}
      {errorMessage && (
        <p className="error" role="alert">
          {errorMessage}
        </p>
      )}

      <label className="field">
        <span>{t('settings.timezone')}</span>
        <input
          list="tz-suggestions"
          value={timezone}
          disabled={readOnly}
          onChange={(e) => setTimezone(e.target.value)}
          required
        />
        <datalist id="tz-suggestions">
          {TZ_SUGGESTIONS.map((z) => (
            <option key={z} value={z} />
          ))}
        </datalist>
      </label>
      {timezoneChanged && <p className="hint">{t('settings.tz_change_notice')}</p>}

      <label className="field">
        <span>{t('settings.min_lead_hours')}</span>
        <input
          type="number"
          min={0}
          max={168}
          value={minLeadHours}
          disabled={readOnly}
          onChange={(e) => setMinLeadHours(Number(e.target.value))}
        />
      </label>

      <label className="field">
        <span>{t('settings.first_day_of_week')}</span>
        <select
          value={firstDayOfWeek}
          disabled={readOnly}
          onChange={(e) => setFirstDayOfWeek(Number(e.target.value))}
        >
          {DAYS_OF_WEEK.map((d) => (
            <option key={d} value={d}>
              {t(`schedule.day.${d}` as StringKey)}
            </option>
          ))}
        </select>
      </label>

      <fieldset className="field">
        <legend>{t('settings.allowed_languages')}</legend>
        <div className="checkbox-grid">
          {LANGS.map((lang) => (
            <label key={lang} className="checkbox">
              <input
                type="checkbox"
                checked={allowed.includes(lang)}
                disabled={readOnly}
                onChange={() => toggleAllowed(lang)}
              />
              <span>{t(`lang.${lang}` as StringKey)}</span>
            </label>
          ))}
        </div>
      </fieldset>

      <label className="field">
        <span>{t('settings.default_language')}</span>
        <select
          value={defaultLanguage}
          disabled={readOnly}
          onChange={(e) => setDefaultLanguage(e.target.value)}
        >
          {allowed.map((lang) => (
            <option key={lang} value={lang}>
              {t(`lang.${lang}` as StringKey)}
            </option>
          ))}
        </select>
      </label>

      {!readOnly && (
        <div className="form-actions">
          <button type="submit" className="primary" disabled={busy}>
            {t('common.save')}
          </button>
        </div>
      )}
    </form>
  )
}
