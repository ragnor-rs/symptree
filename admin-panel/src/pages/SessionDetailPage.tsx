import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { useI18n } from '../i18n/I18nContext'
import { SessionDetailView } from './SessionDetailView'
import type { SessionDetail } from '../api/types'

/** Loads one session and renders the read-only walk + conclusion (AC-XKH348.5). */
export function SessionDetailPage() {
  const { id } = useParams()
  const { api } = useAuth()
  const { t } = useI18n()
  const [detail, setDetail] = useState<SessionDetail | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    api
      .sessionDetail(Number(id))
      .then((d) => {
        if (!cancelled) setDetail(d)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [api, id])

  return (
    <div>
      <Link to="/sessions">{t('common.back')}</Link>
      {loading ? <p>{t('common.loading')}</p> : detail ? <SessionDetailView detail={detail} /> : null}
    </div>
  )
}
