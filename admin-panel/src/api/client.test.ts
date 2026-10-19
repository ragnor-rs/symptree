import { describe, expect, it, vi } from 'vitest'
import { ApiClient, ApiError } from './client'

function jsonResponse(status: number, body: unknown): Response {
  return {
    status,
    ok: status >= 200 && status < 300,
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as Response
}

describe('ApiClient', () => {
  it('attaches Authorization: Bearer <token> when logged in', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, [])) as unknown as typeof fetch
    const client = new ApiClient({
      getToken: () => 'tok-123',
      onUnauthorized: () => {},
      fetchImpl,
    })

    await client.listDoctors()

    expect(fetchImpl).toHaveBeenCalledTimes(1)
    const [, init] = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock.calls[0]
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer tok-123')
  })

  it('omits Authorization when no token is held', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, [])) as unknown as typeof fetch
    const client = new ApiClient({ getToken: () => null, onUnauthorized: () => {}, fetchImpl })

    await client.listDoctors()

    const [, init] = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock.calls[0]
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined()
  })

  it('invokes onUnauthorized and throws on 401 (the 401→login hook)', async () => {
    const onUnauthorized = vi.fn()
    const fetchImpl = vi.fn(async () => jsonResponse(401, { error: 'unauthorized' })) as unknown as typeof fetch
    const client = new ApiClient({ getToken: () => 'expired', onUnauthorized, fetchImpl })

    await expect(client.listDoctors()).rejects.toBeInstanceOf(ApiError)
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })

  it('surfaces the server error code on non-2xx', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(422, { error: 'overlapping_rows' })) as unknown as typeof fetch
    const client = new ApiClient({ getToken: () => 't', onUnauthorized: () => {}, fetchImpl })

    await expect(client.replaceWeeklyHours(1, [])).rejects.toMatchObject({
      status: 422,
      code: 'overlapping_rows',
    })
  })

  it('never sends a clinic_id — the body carries only login fields (AC-XKH348.8)', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, { token: 't' })) as unknown as typeof fetch
    const client = new ApiClient({ getToken: () => null, onUnauthorized: () => {}, fetchImpl })

    await client.login('clinic-a', 'a@x.io', 'pw')

    const [, init] = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock.calls[0]
    const body = JSON.parse(init.body as string)
    expect(body).not.toHaveProperty('clinic_id')
    expect(body).toMatchObject({ clinic_slug: 'clinic-a', email: 'a@x.io' })
  })

  it('updateClinicSettings PUTs the full object and never sends clinic_id (AC-FXJEVE.10)', async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse(200, {
        timezone: 'Asia/Almaty',
        min_lead_hours: 48,
        first_day_of_week: 0,
        default_language: 'kk',
        allowed_languages: ['kk'],
      }),
    ) as unknown as typeof fetch
    const client = new ApiClient({ getToken: () => 't', onUnauthorized: () => {}, fetchImpl })

    await client.updateClinicSettings({
      timezone: 'Asia/Almaty',
      min_lead_hours: 48,
      first_day_of_week: 0,
      default_language: 'kk',
      allowed_languages: ['kk'],
    })

    const [url, init] = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock.calls[0]
    expect(url).toContain('/api/v1/admin/clinic-settings')
    expect(init.method).toBe('PUT')
    const body = JSON.parse(init.body as string)
    expect(body).not.toHaveProperty('clinic_id')
    expect(body.min_lead_hours).toBe(48)
  })
})
