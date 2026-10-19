import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { I18nProvider } from '../i18n/I18nContext'
import { AuthProvider } from '../auth/AuthContext'
import { ScheduleEditor } from './ScheduleEditor'

function jsonResponse(status: number, body: unknown): Response {
  return {
    status,
    ok: status >= 200 && status < 300,
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as Response
}

const SETTINGS = { timezone: 'UTC', first_day_of_week: 1, default_language: 'ru', allowed_languages: ['ru', 'kk'] }

/**
 * Routes the editor's mount-time reads (GET weekly-hours / clinic-settings / time-off)
 * and lets a test override the PUT weekly-hours response.
 */
function makeFetch(putResponse: () => Response = () => jsonResponse(200, { weekly_hours: [] })) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    if (method === 'PUT') return putResponse()
    if (url.includes('/clinic-settings')) return jsonResponse(200, SETTINGS)
    if (url.includes('/weekly-hours')) return jsonResponse(200, { weekly_hours: [] })
    if (url.includes('/time-off')) return jsonResponse(200, [])
    return jsonResponse(200, [])
  })
}

function renderEditor() {
  return render(
    <I18nProvider>
      <AuthProvider>
        <MemoryRouter initialEntries={['/doctors/1/schedule']}>
          <Routes>
            <Route path="/doctors/:id/schedule" element={<ScheduleEditor />} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </I18nProvider>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

function monday(container: HTMLElement): HTMLElement {
  return container.querySelector('[data-day="1"]') as HTMLElement
}

describe('ScheduleEditor (AC-XKH348.4)', () => {
  it('adds and removes split-shift intervals on one day', async () => {
    const fetchMock = makeFetch()
    vi.stubGlobal('fetch', fetchMock)
    const { container } = renderEditor()
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())

    const mon = within(monday(container))
    fireEvent.click(mon.getByRole('button', { name: 'Добавить интервал' }))
    expect(mon.getByLabelText('Понедельник Начало')).toBeInTheDocument()

    // Second interval on the same day == a split shift.
    fireEvent.click(mon.getByRole('button', { name: 'Добавить интервал' }))
    expect(within(monday(container)).getAllByLabelText('Понедельник Начало')).toHaveLength(2)

    // Remove one → back to a single interval.
    fireEvent.click(within(monday(container)).getAllByRole('button', { name: 'Удалить' })[0])
    expect(within(monday(container)).getAllByLabelText('Понедельник Начало')).toHaveLength(1)
  })

  it('blocks save on a client-side inverted range and never calls PUT', async () => {
    const fetchMock = makeFetch()
    vi.stubGlobal('fetch', fetchMock)
    const { container } = renderEditor()
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())

    const mon = within(monday(container))
    fireEvent.click(mon.getByRole('button', { name: 'Добавить интервал' }))
    fireEvent.change(within(monday(container)).getByLabelText('Понедельник Начало'), {
      target: { value: '17:00' },
    })
    fireEvent.change(within(monday(container)).getByLabelText('Понедельник Конец'), {
      target: { value: '09:00' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Конец должен быть позже начала')
    const putCalls = fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')
    expect(putCalls).toHaveLength(0)
  })

  it('surfaces the server 422 message when the backend rejects the hours', async () => {
    const fetchMock = makeFetch(() => jsonResponse(422, { error: 'overlapping_rows' }))
    vi.stubGlobal('fetch', fetchMock)
    const { container } = renderEditor()
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())

    const mon = within(monday(container))
    fireEvent.click(mon.getByRole('button', { name: 'Добавить интервал' }))
    // A single valid interval passes client validation, so the PUT fires and the server 422 wins.
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Интервалы в одном дне не должны пересекаться')
  })
})
