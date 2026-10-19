import { useMemo, useState, type FormEvent } from 'react'
import { useI18n } from '../i18n/I18nContext'
import type { Doctor, DoctorWriteRequest } from '../api/types'

interface DoctorFormProps {
  /** Specialist codes offered for selection — sourced from the API (AC-XKH348.3). */
  availableCodes: string[]
  initial?: Doctor
  onSubmit: (req: DoctorWriteRequest) => void
  onCancel: () => void
}

/**
 * Create/edit form for a doctor. The specialist-code choices are driven by
 * [availableCodes] (the clinic's enabled algorithms' codes) — never a hardcoded
 * list (AC-XKH348.3). Codes already on the doctor but no longer offered stay
 * selectable so an edit never silently drops them.
 */
export function DoctorForm({ availableCodes, initial, onSubmit, onCancel }: DoctorFormProps) {
  const { t, tCode } = useI18n()
  const [fullName, setFullName] = useState(initial?.full_name ?? '')
  const [slotMinutes, setSlotMinutes] = useState(initial?.slot_minutes ?? 30)
  const [active, setActive] = useState(initial?.active ?? true)
  const [codes, setCodes] = useState<string[]>(initial?.specialist_codes ?? [])

  const offered = useMemo(() => {
    const set = new Set<string>([...availableCodes, ...(initial?.specialist_codes ?? [])])
    return [...set].sort()
  }, [availableCodes, initial])

  function toggleCode(code: string) {
    setCodes((prev) => (prev.includes(code) ? prev.filter((c) => c !== code) : [...prev, code]))
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    onSubmit({
      full_name: fullName.trim(),
      specialist_codes: codes,
      slot_minutes: slotMinutes,
      active,
    })
  }

  return (
    <form className="doctor-form" onSubmit={submit} aria-label={t('doctors.new')}>
      <label className="field">
        <span>{t('doctors.full_name')}</span>
        <input value={fullName} onChange={(e) => setFullName(e.target.value)} required />
      </label>

      <fieldset className="field">
        <legend>{t('doctors.specialist_codes')}</legend>
        {offered.length === 0 ? (
          <p className="hint">{t('doctors.no_specialist_codes')}</p>
        ) : (
          <div className="checkbox-grid">
            {offered.map((code) => (
              <label key={code} className="checkbox">
                <input
                  type="checkbox"
                  checked={codes.includes(code)}
                  onChange={() => toggleCode(code)}
                />
                <span>{tCode('specialist', code)}</span>
              </label>
            ))}
          </div>
        )}
      </fieldset>

      <label className="field">
        <span>{t('doctors.slot_minutes')}</span>
        <input
          type="number"
          min={1}
          value={slotMinutes}
          onChange={(e) => setSlotMinutes(Number(e.target.value))}
        />
      </label>

      <label className="checkbox">
        <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
        <span>{t('doctors.active')}</span>
      </label>

      <div className="form-actions">
        <button type="button" className="secondary" onClick={onCancel}>
          {t('common.cancel')}
        </button>
        <button type="submit" className="primary">
          {t('common.save')}
        </button>
      </div>
    </form>
  )
}
