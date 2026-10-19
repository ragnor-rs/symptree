import { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { useI18n } from '../i18n/I18nContext'
import { ApiError } from '../api/client'
import type { ClinicSettings, ClinicSettingsUpdate } from '../api/types'
import { SettingsForm } from './SettingsForm'

/**
 * Clinic-settings section (FR-FXJEVE). Loads the settings via GET, renders the
 * editable form for admins and a read-only view for operators (role from
 * {@link useAuth}), and saves via PUT — mapping the server's 422 code to a
 * localized message. `clinic_id` is never sent; the server derives it from the token.
 */
export function SettingsPage() {
  const { api, role } = useAuth()
  const { t } = useI18n()
  const [settings, setSettings] = useState<ClinicSettings | null>(null)
  const [loadError, setLoadError] = useState(false)
  const [errorCode, setErrorCode] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState(false)
  // Bumped on each successful save so the form re-initializes from fresh values.
  const [version, setVersion] = useState(0)

  useEffect(() => {
    let active = true
    api
      .clinicSettings()
      .then((s) => active && setSettings(s))
      .catch(() => active && setLoadError(true))
    return () => {
      active = false
    }
  }, [api])

  const onSave = useCallback(
    async (req: ClinicSettingsUpdate) => {
      setBusy(true)
      setErrorCode(null)
      setSaved(false)
      try {
        const updated = await api.updateClinicSettings(req)
        setSettings(updated)
        setVersion((v) => v + 1)
        setSaved(true)
      } catch (e) {
        setErrorCode(e instanceof ApiError ? e.code : 'error')
      } finally {
        setBusy(false)
      }
    },
    [api],
  )

  if (loadError) return <p className="error" role="alert">{t('error.generic')}</p>
  if (!settings) return <p>{t('common.loading')}</p>

  return (
    <section className="settings-page">
      <h1>{t('settings.title')}</h1>
      {saved && <p className="success">{t('settings.saved')}</p>}
      <SettingsForm
        key={version}
        initial={settings}
        readOnly={role !== 'admin'}
        busy={busy}
        errorCode={errorCode}
        onSave={onSave}
      />
    </section>
  )
}
