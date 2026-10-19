import { useEffect } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nProvider } from '../i18n/I18nContext'
import { AuthProvider, useAuth } from '../auth/AuthContext'
import { SettingsPage } from './SettingsPage'

function jsonResponse(status: number, body: unknown): Response {
  return {
    status,
    ok: status >= 200 && status < 300,
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as Response
}

const SETTINGS = {
  timezone: 'Asia/Almaty',
  min_lead_hours: 24,
  first_day_of_week: 1,
  default_language: 'ru',
  allowed_languages: ['ru', 'kk'],
}

/**
 * Logs in (so the role lands in AuthContext) then renders the page. The fetch stub
 * answers POST /login with the given role, GET /clinic-settings with SETTINGS, and
 * routes PUT to [putResponse].
 */
function makeFetch(role: 'admin' | 'operator', putResponse: () => Response = () => jsonResponse(200, SETTINGS)) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    if (url.includes('/login')) return jsonResponse(200, { token: 'tok', role })
    if (url.includes('/clinic-settings') && method === 'PUT') return putResponse()
    if (url.includes('/clinic-settings')) return jsonResponse(200, SETTINGS)
    return jsonResponse(200, {})
  })
}

function LoginThenSettings() {
  const { login, isAuthenticated } = useAuth()
  useEffect(() => {
    void login('clinic', 'a@x.io', 'pw').catch(() => {})
  }, [login])
  return isAuthenticated ? <SettingsPage /> : null
}

function renderPage(role: 'admin' | 'operator', putResponse?: () => Response) {
  const fetchMock = makeFetch(role, putResponse)
  vi.stubGlobal('fetch', fetchMock)
  render(
    <I18nProvider>
      <AuthProvider>
        <LoginThenSettings />
      </AuthProvider>
    </I18nProvider>,
  )
  return fetchMock
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('SettingsPage (FR-FXJEVE)', () => {
  it('admin sees an editable form and saving PUTs the object without clinic_id (AC-FXJEVE.10)', async () => {
    const fetchMock = renderPage('admin')
    const saveBtn = await screen.findByRole('button', { name: 'Сохранить' })

    await userEvent.click(saveBtn)

    await waitFor(() => {
      const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')
      expect(put).toBeTruthy()
    })
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(put[0]).toContain('/api/v1/admin/clinic-settings')
    const body = JSON.parse((put[1] as RequestInit).body as string)
    expect(body).not.toHaveProperty('clinic_id')
    expect(body.timezone).toBe('Asia/Almaty')
  })

  it('operator sees a read-only view with no Save button (AC-FXJEVE.11)', async () => {
    renderPage('operator')
    // The loaded value renders…
    await waitFor(() => expect(screen.getByLabelText('Часовой пояс')).toHaveValue('Asia/Almaty'))
    // …but there is no Save affordance.
    expect(screen.queryByRole('button', { name: 'Сохранить' })).toBeNull()
  })

  it('surfaces the server 422 code as a localized error (AC-FXJEVE.10/.12)', async () => {
    renderPage('admin', () => jsonResponse(422, { error: 'invalid_languages' }))
    const saveBtn = await screen.findByRole('button', { name: 'Сохранить' })

    await userEvent.click(saveBtn)

    expect(await screen.findByRole('alert')).toHaveTextContent('Недопустимый набор языков')
  })
})
