// Wire DTOs — these mirror the backend (`core`) admin REST surface exactly
// (FR-MG7TSR + the FR-XKH348 additive read endpoints). snake_case matches the
// JSON on the wire; the SPA never invents fields the server doesn't send and
// never sends `clinic_id` (the server derives it from the token — AC-XKH348.8).

export interface LoginRequest {
  clinic_slug: string
  email: string
  password: string
}

/** Clinic-user role. `admin` may write; `operator` is read-only (FR-MG7TSR). */
export type Role = 'admin' | 'operator'

export interface LoginResponse {
  token: string
  /** The authenticated user's role — advisory UI data; the server enforces via the token. */
  role: Role
}

export interface Doctor {
  id: number
  full_name: string
  specialist_codes: string[]
  slot_minutes: number
  active: boolean
}

export interface DoctorWriteRequest {
  full_name: string
  specialist_codes: string[]
  slot_minutes: number
  active: boolean
}

export interface WeeklyHoursRow {
  day_of_week: number // 1 = Monday … 7 = Sunday
  start_time: string // "HH:mm"
  end_time: string // "HH:mm"
}

export interface ReplaceWeeklyHoursRequest {
  weekly_hours: WeeklyHoursRow[]
}

export interface TimeOff {
  id: number
  start_at: string // ISO-8601 instant
  end_at: string // ISO-8601 instant
  reason: string | null
}

export interface CreateTimeOffRequest {
  start_at: string
  end_at: string
  reason?: string | null
}

export interface SpecialistCodesResponse {
  specialist_codes: string[]
}

export interface ClinicSettings {
  timezone: string
  /** Minimum auto-booking lead time in hours (FR-FXJEVE; default 24). */
  min_lead_hours: number
  /** 0=Sun … 6=Sat; the week's first day for grid ordering (default Monday = 1). */
  first_day_of_week: number
  default_language: string
  allowed_languages: string[]
}

/** FR-FXJEVE — full-object settings write body. Same fields as {@link ClinicSettings}; never carries clinic_id. */
export type ClinicSettingsUpdate = ClinicSettings

export interface SessionSummary {
  id: number
  patient_ref: string
  language: string
  status: string
  created_at: string
}

export interface SessionListResponse {
  sessions: SessionSummary[]
  next_cursor?: number | null
}

export interface SessionTurn {
  node_id?: string | null
  user_text?: string | null
  picked_option?: string | null
  ts?: string | null
}

export type Urgency = 'emergency' | 'urgent' | 'routine'

export type ClinicAction =
  | 'book_appointment'
  | 'bypass_appointment'
  | 'recommend_pharmacy'
  | 'recommend_self_care'

export interface ConclusionTest {
  name: string
  prep?: string | null
}

// Semantic conclusion block (FR-XKH348 backend extension). `urgency`,
// `specialist` and `clinic_action` are semantic codes the SPA localizes;
// `diagnosis`/`action`/`tests` carry algorithm-authored text the backend already
// localized to the session language — the SPA renders them verbatim.
export interface Conclusion {
  urgency: Urgency
  specialist?: string | null
  diagnosis: string
  action: string
  tests: ConclusionTest[]
  clinic_action: ClinicAction
  follow_up_days?: number | null
}

export interface SessionDetail {
  id: number
  patient_ref: string
  platform_user_id: string
  language: string
  status: string
  history: SessionTurn[]
  conclusion?: Conclusion | null
}

export interface Booking {
  id: number
  doctor_name: string
  slot_at: string
  slot_end: string
  status: string
  feedback_score?: number | null
}

export interface SessionListParams {
  status?: string
  from?: string
  to?: string
  cursor?: number
}

export interface BookingListParams {
  status?: string
  from?: string
  to?: string
}
