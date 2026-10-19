import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nProvider } from '../i18n/I18nContext'
import { SettingsForm } from './SettingsForm'
import type { ClinicSettings } from '../api/types'

const SETTINGS: ClinicSettings = {
  timezone: 'Asia/Almaty',
  min_lead_hours: 24,
  first_day_of_week: 1,
  default_language: 'ru',
  allowed_languages: ['ru', 'kk'],
}

function renderForm(props: Partial<Parameters<typeof SettingsForm>[0]> = {}) {
  return render(
    <I18nProvider>
      <SettingsForm initial={SETTINGS} readOnly={false} onSave={vi.fn()} {...props} />
    </I18nProvider>,
  )
}

describe('SettingsForm (FR-FXJEVE)', () => {
  it('admin can edit and saves the full object with NO clinic_id (AC-FXJEVE.10)', async () => {
    const onSave = vi.fn()
    renderForm({ onSave })
    const user = userEvent.setup()

    const lead = screen.getByLabelText('Мин. время до записи (ч)')
    await user.clear(lead)
    await user.type(lead, '48')
    await user.click(screen.getByRole('button', { name: 'Сохранить' }))

    expect(onSave).toHaveBeenCalledTimes(1)
    const req = onSave.mock.calls[0][0]
    expect(req).not.toHaveProperty('clinic_id')
    expect(req.min_lead_hours).toBe(48)
    expect(req.timezone).toBe('Asia/Almaty')
    expect(req.default_language).toBe('ru')
    expect(req.allowed_languages).toEqual(['ru', 'kk'])
  })

  it('renders read-only for an operator: no Save button, inputs disabled (AC-FXJEVE.11)', () => {
    renderForm({ readOnly: true })
    expect(screen.queryByRole('button', { name: 'Сохранить' })).toBeNull()
    expect(screen.getByLabelText('Мин. время до записи (ч)')).toBeDisabled()
  })

  it('shows the timezone-change notice only after the timezone is changed (AC-FXJEVE.11)', async () => {
    renderForm()
    const user = userEvent.setup()
    const notice = 'Изменение часового пояса сдвинет отображаемое время существующих записей (сами записи не меняются).'

    expect(screen.queryByText(notice)).toBeNull()
    const tz = screen.getByLabelText('Часовой пояс')
    await user.clear(tz)
    await user.type(tz, 'UTC')
    expect(screen.getByText(notice)).toBeInTheDocument()
  })

  it('surfaces a localized validation error from the server (AC-FXJEVE.10/.12)', () => {
    renderForm({ errorCode: 'invalid_languages' })
    expect(screen.getByRole('alert')).toHaveTextContent('Недопустимый набор языков')
  })
})
