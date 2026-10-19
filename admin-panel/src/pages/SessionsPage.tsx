import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { useI18n } from '../i18n/I18nContext'
import { formatInstant } from '../lib/format'
import type { SessionSummary } from '../api/types'

/** Paginated symptomizer-results list (AC-XKH348.5). Read-only; detail lives at /sessions/:id. */
export function SessionsPage() {
  const { api } = useAuth()
  const { t, lang } = useI18n()
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [nextCursor, setNextCursor] = useState<number | null>(null)
  const [loading, setLoading] = useState(true)

  const load = useCallback(
    async (fromCursor?: number) => {
      setLoading(true)
      try {
        const res = await api.listSessions(fromCursor ? { cursor: fromCursor } : undefined)
        setSessions((prev) => (fromCursor ? [...prev, ...res.sessions] : res.sessions))
        setNextCursor(res.next_cursor ?? null)
      } finally {
        setLoading(false)
      }
    },
    [api],
  )

  useEffect(() => {
    void load()
  }, [load])

  function loadMore() {
    if (nextCursor != null) void load(nextCursor)
  }

  return (
    <section>
      <h1>{t('sessions.title')}</h1>
      {loading && sessions.length === 0 ? (
        <p>{t('common.loading')}</p>
      ) : sessions.length === 0 ? (
        <p className="empty">{t('common.empty')}</p>
      ) : (
        <table className="grid">
          <thead>
            <tr>
              <th>{t('sessions.patient')}</th>
              <th>{t('sessions.language')}</th>
              <th>{t('sessions.status')}</th>
              <th>{t('sessions.created')}</th>
            </tr>
          </thead>
          <tbody>
            {sessions.map((s) => (
              <tr key={s.id}>
                <td>
                  <Link to={`/sessions/${s.id}`}>{s.patient_ref}</Link>
                </td>
                <td>{s.language.toUpperCase()}</td>
                <td>{s.status}</td>
                <td>{formatInstant(s.created_at, lang)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {nextCursor != null && (
        <button type="button" onClick={loadMore} disabled={loading}>
          {t('sessions.load_more')}
        </button>
      )}
    </section>
  )
}
