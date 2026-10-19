import { useState, type FormEvent } from 'react'
import { useAuth } from '../auth/AuthContext'
import { useI18n } from '../i18n/I18nContext'
import { LANGS } from '../i18n/strings'

/** Clinic-admin login (AC-XKH348.2). On success the token is held in memory by AuthProvider. */
export function LoginPage() {
  const { t, lang, setLang } = useI18n()
  const { login } = useAuth()
  const [clinicSlug, setClinicSlug] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(false)
  const [busy, setBusy] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError(false)
    setBusy(true)
    try {
      await login(clinicSlug.trim(), email.trim(), password)
    } catch {
      setError(true)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-screen">
      <form className="login-card" onSubmit={onSubmit}>
        <div className="lang-toggle login-lang" role="group" aria-label="language">
          {LANGS.map((l) => (
            <button
              key={l}
              type="button"
              className={l === lang ? 'lang active' : 'lang'}
              aria-pressed={l === lang}
              onClick={() => setLang(l)}
            >
              {l.toUpperCase()}
            </button>
          ))}
        </div>
        <h1>{t('login.title')}</h1>
        <label className="field">
          <span>{t('login.clinic_slug')}</span>
          <input value={clinicSlug} onChange={(e) => setClinicSlug(e.target.value)} autoFocus />
        </label>
        <label className="field">
          <span>{t('login.email')}</span>
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label className="field">
          <span>{t('login.password')}</span>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        {error && <p className="error" role="alert">{t('login.failed')}</p>}
        <button type="submit" className="primary" disabled={busy}>
          {busy ? t('common.loading') : t('login.submit')}
        </button>
      </form>
    </div>
  )
}
