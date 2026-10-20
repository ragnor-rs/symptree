import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { useI18n } from '../i18n/I18nContext'
import type { StringKey } from '../i18n/strings'
import { ApiError } from '../api/client'
import { errorKeyFor } from '../lib/format'
import {
  DEFAULT_FIRST_DAY,
  emptyGrid,
  gridToRows,
  orderedDays,
  rowsToGrid,
  validateGrid,
  type WeeklyGrid,
} from '../lib/schedule'
import type { TimeOff } from '../api/types'

/**
 * Per-doctor schedule editor (AC-XKH348.4): a 7-day weekly-hours grid with
 * split shifts (multiple intervals per day; empty day = day off) saved via PUT,
 * plus an add/remove time-off list. Client-side validation mirrors the server's
 * `end > start` / no-overlap rules and surfaces the server's 422 on failure.
 *
 * Weekly hours are replace-on-save: the PUT endpoint replaces the doctor's full
 * set. The grid is seeded on mount from GET weekly-hours (see the effect below).
 */
export function ScheduleEditor() {
  const { id } = useParams()
  const doctorId = Number(id)
  const { api } = useAuth()
  const { t } = useI18n()

  const [grid, setGrid] = useState<WeeklyGrid>(emptyGrid())
  const [firstDay, setFirstDay] = useState(DEFAULT_FIRST_DAY)
  const [timeOff, setTimeOff] = useState<TimeOff[]>([])
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [newFrom, setNewFrom] = useState('')
  const [newTo, setNewTo] = useState('')
  const [newReason, setNewReason] = useState('')

  const reloadTimeOff = useCallback(async () => {
    setTimeOff(await api.listTimeOff(doctorId))
  }, [api, doctorId])

  // Seed the grid from the doctor's persisted hours, the day order from the
  // clinic's first-day setting, and the time-off list — all on first load.
  useEffect(() => {
    let cancelled = false
    void (async () => {
      const [hours, settings, off] = await Promise.all([
        api.weeklyHours(doctorId),
        api.clinicSettings(),
        api.listTimeOff(doctorId),
      ])
      if (cancelled) return
      setGrid(rowsToGrid(hours.weekly_hours))
      setFirstDay(settings.first_day_of_week)
      setTimeOff(off)
    })()
    return () => {
      cancelled = true
    }
  }, [api, doctorId])

  function addInterval(day: number) {
    setGrid((g) => ({ ...g, [day]: [...g[day], { start: '09:00', end: '17:00' }] }))
    setSaved(false)
  }

  function removeInterval(day: number, idx: number) {
    setGrid((g) => ({ ...g, [day]: g[day].filter((_, i) => i !== idx) }))
    setSaved(false)
  }

  function updateInterval(day: number, idx: number, field: 'start' | 'end', value: string) {
    setGrid((g) => ({
      ...g,
      [day]: g[day].map((iv, i) => (i === idx ? { ...iv, [field]: value } : iv)),
    }))
    setSaved(false)
  }

  async function saveWeekly() {
    setError(null)
    setSaved(false)
    const clientError = validateGrid(grid)
    if (clientError) {
      setError(clientError)
      return
    }
    try {
      await api.replaceWeeklyHours(doctorId, gridToRows(grid))
      setSaved(true)
    } catch (e) {
      setError(e instanceof ApiError ? e.code : 'generic')
    }
  }

  async function addTimeOff() {
    setError(null)
    if (!newFrom || !newTo) {
      setError('missing_dates')
      return
    }
    try {
      await api.addTimeOff(doctorId, {
        start_at: new Date(newFrom).toISOString(),
        end_at: new Date(newTo).toISOString(),
        reason: newReason.trim() || null,
      })
      setNewFrom('')
      setNewTo('')
      setNewReason('')
      await reloadTimeOff()
    } catch (e) {
      setError(e instanceof ApiError ? e.code : 'generic')
    }
  }

  async function removeTimeOff(timeOffId: number) {
    await api.deleteTimeOff(doctorId, timeOffId)
    await reloadTimeOff()
  }

  return (
    <section className="schedule-editor">
      <div className="page-head">
        <h1>{t('schedule.title')}</h1>
        <Link to="/doctors">{t('common.back')}</Link>
      </div>

      {error && (
        <p className="error" role="alert">
          {t(errorKeyFor(error))}
        </p>
      )}

      <h2>{t('schedule.weekly_hours')}</h2>
      <div className="weekly-grid">
        {orderedDays(firstDay).map((day) => (
          <div key={day} className="weekday" data-day={day}>
            <div className="weekday-head">
              <strong>{t(`schedule.day.${day}` as StringKey)}</strong>
              <button type="button" onClick={() => addInterval(day)}>
                {t('schedule.add_interval')}
              </button>
            </div>
            {grid[day].length === 0 ? (
              <span className="day-off">{t('schedule.day_off')}</span>
            ) : (
              grid[day].map((iv, idx) => (
                <div key={idx} className="interval">
                  <input
                    type="time"
                    aria-label={`${t(`schedule.day.${day}` as StringKey)} ${t('schedule.start')}`}
                    value={iv.start}
                    onChange={(e) => updateInterval(day, idx, 'start', e.target.value)}
                  />
                  <span>–</span>
                  <input
                    type="time"
                    aria-label={`${t(`schedule.day.${day}` as StringKey)} ${t('schedule.end')}`}
                    value={iv.end}
                    onChange={(e) => updateInterval(day, idx, 'end', e.target.value)}
                  />
                  <button type="button" className="danger-text" onClick={() => removeInterval(day, idx)}>
                    {t('common.remove')}
                  </button>
                </div>
              ))
            )}
          </div>
        ))}
      </div>
      <button type="button" className="primary" onClick={saveWeekly}>
        {t('common.save')}
      </button>
      {saved && <span className="saved-ok"> ✓</span>}

      <h2>{t('schedule.time_off')}</h2>
      <div className="time-off-add">
        <label className="field">
          <span>{t('schedule.from')}</span>
          <input type="datetime-local" value={newFrom} onChange={(e) => setNewFrom(e.target.value)} />
        </label>
        <label className="field">
          <span>{t('schedule.to')}</span>
          <input type="datetime-local" value={newTo} onChange={(e) => setNewTo(e.target.value)} />
        </label>
        <label className="field">
          <span>{t('schedule.reason')}</span>
          <input value={newReason} onChange={(e) => setNewReason(e.target.value)} />
        </label>
        <button type="button" onClick={addTimeOff}>
          {t('schedule.add_time_off')}
        </button>
      </div>
      {timeOff.length === 0 ? (
        <p className="empty">{t('schedule.no_time_off')}</p>
      ) : (
        <ul className="time-off-list">
          {timeOff.map((to) => (
            <li key={to.id}>
              {to.start_at} → {to.end_at}
              {to.reason ? ` · ${to.reason}` : ''}
              <button type="button" className="danger-text" onClick={() => removeTimeOff(to.id)}>
                {t('common.remove')}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
