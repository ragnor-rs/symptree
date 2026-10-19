import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { I18nProvider } from '../i18n/I18nContext'
import { SessionDetailView } from './SessionDetailView'
import type { SessionDetail } from '../api/types'

function renderView(detail: SessionDetail) {
  return render(
    <I18nProvider>
      <SessionDetailView detail={detail} />
    </I18nProvider>,
  )
}

const baseDetail: SessionDetail = {
  id: 1,
  patient_ref: 'p_abc123',
  platform_user_id: 'tg-1',
  language: 'ru',
  status: 'concluded',
  history: [
    { node_id: 'q1', picked_option: 'yes' },
    { node_id: 'q2', user_text: 'болит голова' },
  ],
  conclusion: {
    urgency: 'urgent',
    specialist: 'cardiologist',
    diagnosis: 'Срочный диагноз',
    action: 'Запишитесь к врачу',
    tests: [{ name: 'ЭКГ', prep: 'Натощак' }],
    clinic_action: 'book_appointment',
    follow_up_days: 7,
  },
}

describe('SessionDetailView (AC-XKH348.5)', () => {
  it('renders the walked Q→A path', () => {
    renderView(baseDetail)
    expect(screen.getByText('q1')).toBeInTheDocument()
    expect(screen.getByText(/болит голова/)).toBeInTheDocument()
  })

  it('renders the conclusion: localized urgency badge + specialist + verbatim algorithm text', () => {
    renderView(baseDetail)
    expect(screen.getByText('Срочно')).toBeInTheDocument() // urgency code → ru label
    expect(screen.getByText('Кардиолог')).toBeInTheDocument() // specialist code → ru label
    expect(screen.getByText('Срочный диагноз')).toBeInTheDocument() // algorithm text, verbatim
    expect(screen.getByText('ЭКГ')).toBeInTheDocument()
    expect(screen.getByText(/Натощак/)).toBeInTheDocument()
  })

  it('shows the urgency as a color-coded badge carrying the semantic code', () => {
    const { container } = renderView(baseDetail)
    const badge = container.querySelector('[data-urgency="urgent"]')
    expect(badge).not.toBeNull()
  })

  it('renders a placeholder when the session has no conclusion', () => {
    renderView({ ...baseDetail, status: 'active', conclusion: null })
    expect(screen.getByText(/Заключение отсутствует/)).toBeInTheDocument()
  })
})
