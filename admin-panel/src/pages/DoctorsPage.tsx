import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { useI18n } from '../i18n/I18nContext'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { DoctorForm } from './DoctorForm'
import type { Doctor, DoctorWriteRequest } from '../api/types'

type Editing = { mode: 'new' } | { mode: 'edit'; doctor: Doctor } | null

/** Doctors management: list (with a soft-deleted toggle), create, edit, soft-delete (AC-XKH348.3). */
export function DoctorsPage() {
  const { api } = useAuth()
  const { t, tCode } = useI18n()
  const [active, setActive] = useState<Doctor[]>([])
  const [all, setAll] = useState<Doctor[]>([])
  const [availableCodes, setAvailableCodes] = useState<string[]>([])
  const [includeDeleted, setIncludeDeleted] = useState(false)
  const [editing, setEditing] = useState<Editing>(null)
  const [confirmDelete, setConfirmDelete] = useState<Doctor | null>(null)
  const [loading, setLoading] = useState(true)

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      const [activeList, allList, codes] = await Promise.all([
        api.listDoctors(false),
        api.listDoctors(true),
        api.specialistCodes(),
      ])
      setActive(activeList)
      setAll(allList)
      setAvailableCodes(codes.specialist_codes)
    } finally {
      setLoading(false)
    }
  }, [api])

  useEffect(() => {
    void reload()
  }, [reload])

  const activeIds = new Set(active.map((d) => d.id))
  const rows = includeDeleted ? all : active

  async function submitForm(req: DoctorWriteRequest) {
    if (editing?.mode === 'edit') {
      await api.updateDoctor(editing.doctor.id, req)
    } else {
      await api.createDoctor(req)
    }
    setEditing(null)
    await reload()
  }

  async function doDelete() {
    if (!confirmDelete) return
    await api.deleteDoctor(confirmDelete.id)
    setConfirmDelete(null)
    await reload()
  }

  return (
    <section>
      <div className="page-head">
        <h1>{t('doctors.title')}</h1>
        <div className="page-actions">
          <label className="checkbox inline">
            <input
              type="checkbox"
              checked={includeDeleted}
              onChange={(e) => setIncludeDeleted(e.target.checked)}
            />
            <span>{t('doctors.show_deleted')}</span>
          </label>
          <button type="button" className="primary" onClick={() => setEditing({ mode: 'new' })}>
            {t('doctors.new')}
          </button>
        </div>
      </div>

      {loading ? (
        <p>{t('common.loading')}</p>
      ) : rows.length === 0 ? (
        <p className="empty">{t('common.empty')}</p>
      ) : (
        <table className="grid">
          <thead>
            <tr>
              <th>{t('doctors.full_name')}</th>
              <th>{t('doctors.source')}</th>
              <th>{t('doctors.specialist_codes')}</th>
              <th>{t('doctors.slot_minutes')}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((d) => {
              const deleted = !activeIds.has(d.id)
              return (
                <tr key={d.id} className={deleted ? 'row-deleted' : undefined}>
                  <td>
                    {d.full_name}
                    {deleted && <span className="tag muted"> · {t('common.restore_hint')}</span>}
                  </td>
                  <td>
                    <span className="badge source">{t('doctors.source.internal')}</span>
                  </td>
                  <td>{d.specialist_codes.map((c) => tCode('specialist', c)).join(', ') || t('common.none')}</td>
                  <td>{d.slot_minutes}</td>
                  <td className="row-actions">
                    <button type="button" onClick={() => setEditing({ mode: 'edit', doctor: d })}>
                      {t('common.edit')}
                    </button>
                    <Link to={`/doctors/${d.id}/schedule`}>{t('doctors.schedule')}</Link>
                    {!deleted && (
                      <button type="button" className="danger-text" onClick={() => setConfirmDelete(d)}>
                        {t('common.delete')}
                      </button>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}

      {editing && (
        <div className="modal-backdrop" role="dialog" aria-modal="true">
          <div className="modal">
            <h2>{editing.mode === 'edit' ? t('common.edit') : t('doctors.new')}</h2>
            <DoctorForm
              availableCodes={availableCodes}
              initial={editing.mode === 'edit' ? editing.doctor : undefined}
              onSubmit={submitForm}
              onCancel={() => setEditing(null)}
            />
          </div>
        </div>
      )}

      {confirmDelete && (
        <ConfirmDialog
          message={t('doctors.confirm_delete')}
          onConfirm={doDelete}
          onCancel={() => setConfirmDelete(null)}
        />
      )}
    </section>
  )
}
