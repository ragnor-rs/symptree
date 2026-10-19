import { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { useI18n } from '../i18n/I18nContext'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { ApiError } from '../api/client'
import { errorKeyFor, formatInstant } from '../lib/format'
import type { Booking } from '../api/types'

/** Bookings list + cancel-with-reason (AC-XKH348.6). */
export function BookingsPage() {
  const { api } = useAuth()
  const { t, lang } = useI18n()
  const [bookings, setBookings] = useState<Booking[]>([])
  const [loading, setLoading] = useState(true)
  const [confirm, setConfirm] = useState<Booking | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      setBookings(await api.listBookings())
    } finally {
      setLoading(false)
    }
  }, [api])

  useEffect(() => {
    void reload()
  }, [reload])

  async function doCancel(reason: string) {
    if (!confirm) return
    setError(null)
    setNotice(null)
    try {
      const result = await api.cancelBooking(confirm.id, reason)
      setConfirm(null)
      // The cancel succeeded; warn if the patient couldn't be notified (best-effort push).
      if (!result.notified) setNotice(t('bookings.cancelled_not_notified'))
      await reload()
    } catch (e) {
      setError(e instanceof ApiError ? e.code : 'generic')
      setConfirm(null)
    }
  }

  return (
    <section>
      <h1>{t('bookings.title')}</h1>
      {error && (
        <p className="error" role="alert">
          {t(errorKeyFor(error))}
        </p>
      )}
      {notice && (
        <p className="notice" role="status">
          {notice}
        </p>
      )}
      {loading ? (
        <p>{t('common.loading')}</p>
      ) : bookings.length === 0 ? (
        <p className="empty">{t('common.empty')}</p>
      ) : (
        <table className="grid">
          <thead>
            <tr>
              <th>{t('bookings.doctor')}</th>
              <th>{t('bookings.slot')}</th>
              <th>{t('bookings.status')}</th>
              <th>{t('bookings.feedback')}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {bookings.map((b) => (
              <tr key={b.id}>
                <td>{b.doctor_name}</td>
                <td>{formatInstant(b.slot_at, lang)}</td>
                <td>{b.status}</td>
                <td>{b.feedback_score ?? t('common.none')}</td>
                <td>
                  {b.status === 'confirmed' && (
                    <button type="button" className="danger-text" onClick={() => setConfirm(b)}>
                      {t('bookings.cancel')}
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {confirm && (
        <ConfirmDialog
          message={t('bookings.confirm_cancel')}
          withReason
          reasonLabel={t('bookings.cancel_reason')}
          onConfirm={doCancel}
          onCancel={() => setConfirm(null)}
        />
      )}
    </section>
  )
}
