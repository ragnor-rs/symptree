import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { useI18n } from '../i18n/I18nContext'
import { LANGS } from '../i18n/strings'

/** App chrome: section nav, language toggle (persisted), and logout. */
export function Layout() {
  const { t, lang, setLang } = useI18n()
  const { logout } = useAuth()

  return (
    <div className="layout">
      <header className="topbar">
        <span className="brand">{t('app.title')}</span>
        <nav className="nav">
          <NavLink to="/doctors">{t('nav.doctors')}</NavLink>
          <NavLink to="/sessions">{t('nav.sessions')}</NavLink>
          <NavLink to="/bookings">{t('nav.bookings')}</NavLink>
          <NavLink to="/settings">{t('nav.settings')}</NavLink>
        </nav>
        <div className="topbar-right">
          <div className="lang-toggle" role="group" aria-label="language">
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
          <button type="button" className="logout" onClick={logout}>
            {t('action.logout')}
          </button>
        </div>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  )
}
