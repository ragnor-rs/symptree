import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nProvider } from '../i18n/I18nContext'
import { DoctorForm } from './DoctorForm'
import type { Doctor } from '../api/types'

function renderForm(props: Parameters<typeof DoctorForm>[0]) {
  return render(
    <I18nProvider>
      <DoctorForm {...props} />
    </I18nProvider>,
  )
}

describe('DoctorForm', () => {
  it('offers the specialist codes supplied by the API (AC-XKH348.3)', () => {
    renderForm({ availableCodes: ['cardiologist', 'ent'], onSubmit: vi.fn(), onCancel: vi.fn() })
    // Localized labels: cardiologist has a ru catalog entry; an unknown code falls back to the raw code.
    expect(screen.getByText('Кардиолог')).toBeInTheDocument()
    expect(screen.getByText('ЛОР')).toBeInTheDocument()
  })

  it('submits the selected codes + fields (no clinic_id)', async () => {
    const onSubmit = vi.fn()
    renderForm({ availableCodes: ['cardiologist', 'ent'], onSubmit, onCancel: vi.fn() })
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('ФИО'), 'Иванов И.И.')
    await user.click(screen.getByLabelText('Кардиолог'))
    await user.click(screen.getByRole('button', { name: 'Сохранить' }))

    expect(onSubmit).toHaveBeenCalledTimes(1)
    const req = onSubmit.mock.calls[0][0]
    expect(req.full_name).toBe('Иванов И.И.')
    expect(req.specialist_codes).toEqual(['cardiologist'])
    expect(req).not.toHaveProperty('clinic_id')
  })

  it('pre-selects an edited doctor’s existing codes even if no longer offered', () => {
    const initial: Doctor = {
      id: 7,
      full_name: 'Петров П.П.',
      specialist_codes: ['neurologist'],
      slot_minutes: 20,
      active: true,
    }
    renderForm({ availableCodes: ['cardiologist'], initial, onSubmit: vi.fn(), onCancel: vi.fn() })
    const neuro = screen.getByLabelText('Невролог') as HTMLInputElement
    expect(neuro.checked).toBe(true)
  })
})
