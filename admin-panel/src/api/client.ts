import type {
  Booking,
  BookingListParams,
  ClinicSettings,
  ClinicSettingsUpdate,
  CreateTimeOffRequest,
  Doctor,
  DoctorWriteRequest,
  LoginResponse,
  ReplaceWeeklyHoursRequest,
  SessionDetail,
  SessionListParams,
  SessionListResponse,
  SpecialistCodesResponse,
  TimeOff,
  WeeklyHoursRow,
} from './types'

/** A structured API failure carrying the HTTP status + the server's `error` code. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
  ) {
    super(`${status} ${code}`)
    this.name = 'ApiError'
  }
}

export interface ApiClientOptions {
  /** Returns the current bearer token, or null when logged out. */
  getToken: () => string | null
  /** Invoked once whenever the server answers 401 — the app routes back to login. */
  onUnauthorized: () => void
  /** Base URL prefix; empty in the browser (same-origin via the Vite proxy). */
  baseUrl?: string
  /** Injectable fetch — overridden in tests. */
  fetchImpl?: typeof fetch
}

type Query = Record<string, string | number | boolean | undefined>

function queryString(params?: Query): string {
  if (!params) return ''
  const pairs = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
  return pairs.length ? `?${pairs.join('&')}` : ''
}

/**
 * The single HTTP gateway to `core`'s admin surface. It centralizes the two
 * cross-cutting auth rules (AC-XKH348.2): every request carries
 * `Authorization: Bearer <token>` when logged in, and any 401 triggers
 * `onUnauthorized` so the app can clear the token and return to login.
 */
export class ApiClient {
  constructor(private readonly opts: ApiClientOptions) {}

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = {}
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    const token = this.opts.getToken()
    if (token) headers['Authorization'] = `Bearer ${token}`

    // Bind the default fetch to globalThis — an unbound reference throws
    // "Illegal invocation" in browsers. Tests inject `fetchImpl` directly.
    const doFetch = this.opts.fetchImpl ?? globalThis.fetch.bind(globalThis)
    const res = await doFetch((this.opts.baseUrl ?? '') + path, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })

    if (res.status === 401) {
      this.opts.onUnauthorized()
      throw new ApiError(401, 'unauthorized')
    }
    if (!res.ok) {
      throw new ApiError(res.status, await readErrorCode(res))
    }
    if (res.status === 204) return undefined as T
    return (await res.json()) as T
  }

  // --- auth ---
  login(clinicSlug: string, email: string, password: string): Promise<LoginResponse> {
    return this.request('POST', '/api/v1/admin/login', {
      clinic_slug: clinicSlug,
      email,
      password,
    })
  }

  // --- doctors + schedule (AC-XKH348.3 / .4) ---
  listDoctors(includeDeleted = false): Promise<Doctor[]> {
    return this.request('GET', `/api/v1/admin/doctors${queryString({ include_deleted: includeDeleted || undefined })}`)
  }

  createDoctor(req: DoctorWriteRequest): Promise<Doctor> {
    return this.request('POST', '/api/v1/admin/doctors', req)
  }

  updateDoctor(id: number, req: DoctorWriteRequest): Promise<Doctor> {
    return this.request('PATCH', `/api/v1/admin/doctors/${id}`, req)
  }

  deleteDoctor(id: number): Promise<void> {
    return this.request('DELETE', `/api/v1/admin/doctors/${id}`)
  }

  specialistCodes(): Promise<SpecialistCodesResponse> {
    return this.request('GET', '/api/v1/admin/specialist-codes')
  }

  clinicSettings(): Promise<ClinicSettings> {
    return this.request('GET', '/api/v1/admin/clinic-settings')
  }

  // --- clinic settings write (FR-FXJEVE; admin-only server-side) ---
  updateClinicSettings(body: ClinicSettingsUpdate): Promise<ClinicSettings> {
    return this.request('PUT', '/api/v1/admin/clinic-settings', body)
  }

  weeklyHours(doctorId: number): Promise<ReplaceWeeklyHoursRequest> {
    return this.request('GET', `/api/v1/admin/doctors/${doctorId}/weekly-hours`)
  }

  replaceWeeklyHours(doctorId: number, weeklyHours: WeeklyHoursRow[]): Promise<ReplaceWeeklyHoursRequest> {
    return this.request('PUT', `/api/v1/admin/doctors/${doctorId}/weekly-hours`, {
      weekly_hours: weeklyHours,
    })
  }

  listTimeOff(doctorId: number): Promise<TimeOff[]> {
    return this.request('GET', `/api/v1/admin/doctors/${doctorId}/time-off`)
  }

  addTimeOff(doctorId: number, req: CreateTimeOffRequest): Promise<TimeOff> {
    return this.request('POST', `/api/v1/admin/doctors/${doctorId}/time-off`, req)
  }

  deleteTimeOff(doctorId: number, timeOffId: number): Promise<void> {
    return this.request('DELETE', `/api/v1/admin/doctors/${doctorId}/time-off/${timeOffId}`)
  }

  // --- sessions (AC-XKH348.5) ---
  listSessions(params?: SessionListParams): Promise<SessionListResponse> {
    return this.request('GET', `/api/v1/admin/sessions${queryString(params as Query)}`)
  }

  sessionDetail(id: number): Promise<SessionDetail> {
    return this.request('GET', `/api/v1/admin/sessions/${id}`)
  }

  // --- bookings (AC-XKH348.6) ---
  listBookings(params?: BookingListParams): Promise<Booking[]> {
    return this.request('GET', `/api/v1/admin/bookings${queryString(params as Query)}`)
  }

  cancelBooking(id: number, reason: string): Promise<{ id: number; status: string; notified: boolean }> {
    return this.request('PATCH', `/api/v1/admin/bookings/${id}`, { action: 'cancel', reason })
  }
}

async function readErrorCode(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as { error?: string }
    return body.error ?? 'error'
  } catch {
    return 'error'
  }
}
