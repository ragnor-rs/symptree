import type { WeeklyHoursRow } from '../api/types'

/** One editable interval in the weekly-hours grid. */
export interface Interval {
  start: string // "HH:mm"
  end: string // "HH:mm"
}

/**
 * The grid model: day-of-week → its intervals (empty = day off).
 *
 * Day numbering is the BACKEND/scheduler canonical convention — `0=Sun .. 6=Sat`
 * (Postgres `EXTRACT(DOW)`, `CHECK (day_of_week BETWEEN 0 AND 6)`). This is the
 * storage convention; visual ordering (Monday-first vs Sunday-first) is a
 * separate per-clinic display concern handled by {@link orderedDays}.
 */
export type WeeklyGrid = Record<number, Interval[]>

/** Canonical day set, 0=Sun .. 6=Sat (storage order, NOT display order). */
export const DAYS = [0, 1, 2, 3, 4, 5, 6] as const

/** Monday in the canonical 0=Sun..6=Sat convention — the default first day of the week. */
export const DEFAULT_FIRST_DAY = 1

export function emptyGrid(): WeeklyGrid {
  return { 0: [], 1: [], 2: [], 3: [], 4: [], 5: [], 6: [] }
}

/**
 * The seven day indices in DISPLAY order, starting at [firstDay] and wrapping —
 * e.g. firstDay=1 (Monday) → [1,2,3,4,5,6,0]; firstDay=0 (Sunday) → [0..6]. The
 * clinic's `first_day_of_week` setting drives this; values stay canonical 0–6.
 */
export function orderedDays(firstDay: number): number[] {
  const start = ((firstDay % 7) + 7) % 7
  return Array.from({ length: 7 }, (_, i) => (start + i) % 7)
}

export type ScheduleError = 'inverted_range' | 'overlapping_rows'

/**
 * Mirrors the server's weekly-hours validation (AC-MG7TSR.5) so the SPA can
 * reject locally before the PUT: every interval must satisfy `end > start`, and
 * no two intervals on the SAME day may overlap (half-open `[start, end)`). Times
 * are fixed-width "HH:mm", so lexical comparison equals chronological.
 */
export function validateGrid(grid: WeeklyGrid): ScheduleError | null {
  for (const day of DAYS) {
    const intervals = grid[day] ?? []
    for (const iv of intervals) {
      if (!(iv.end > iv.start)) return 'inverted_range'
    }
    const sorted = [...intervals].sort((a, b) => a.start.localeCompare(b.start))
    for (let i = 1; i < sorted.length; i++) {
      if (sorted[i].start < sorted[i - 1].end) return 'overlapping_rows'
    }
  }
  return null
}

/** Flatten the grid into the wire shape the PUT endpoint expects. */
export function gridToRows(grid: WeeklyGrid): WeeklyHoursRow[] {
  const rows: WeeklyHoursRow[] = []
  for (const day of DAYS) {
    for (const iv of grid[day] ?? []) {
      rows.push({ day_of_week: day, start_time: iv.start, end_time: iv.end })
    }
  }
  return rows
}

/** Rebuild a grid from wire rows (used when seeding the editor from saved hours). */
export function rowsToGrid(rows: WeeklyHoursRow[]): WeeklyGrid {
  const grid = emptyGrid()
  for (const r of rows) {
    ;(grid[r.day_of_week] ??= []).push({ start: r.start_time, end: r.end_time })
  }
  return grid
}
