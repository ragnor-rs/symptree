import { describe, expect, it } from 'vitest'
import { DEFAULT_FIRST_DAY, emptyGrid, gridToRows, orderedDays, rowsToGrid, validateGrid } from './schedule'

describe('weekly-hours validation (mirrors server AC-MG7TSR.5)', () => {
  it('accepts a valid split shift on one day', () => {
    const grid = emptyGrid()
    grid[1] = [
      { start: '09:00', end: '12:00' },
      { start: '13:00', end: '17:00' },
    ]
    expect(validateGrid(grid)).toBeNull()
  })

  it('rejects an inverted interval (end <= start)', () => {
    const grid = emptyGrid()
    grid[2] = [{ start: '17:00', end: '09:00' }]
    expect(validateGrid(grid)).toBe('inverted_range')
  })

  it('rejects overlapping intervals on the same day', () => {
    const grid = emptyGrid()
    grid[3] = [
      { start: '09:00', end: '13:00' },
      { start: '12:00', end: '15:00' },
    ]
    expect(validateGrid(grid)).toBe('overlapping_rows')
  })

  it('allows the same interval on different days', () => {
    const grid = emptyGrid()
    grid[1] = [{ start: '09:00', end: '17:00' }]
    grid[2] = [{ start: '09:00', end: '17:00' }]
    expect(validateGrid(grid)).toBeNull()
  })

  it('round-trips grid ⇄ rows', () => {
    const grid = emptyGrid()
    grid[1] = [{ start: '09:00', end: '12:00' }]
    grid[5] = [{ start: '14:00', end: '18:00' }]
    const rows = gridToRows(grid)
    expect(rows).toEqual([
      { day_of_week: 1, start_time: '09:00', end_time: '12:00' },
      { day_of_week: 5, start_time: '14:00', end_time: '18:00' },
    ])
    expect(rowsToGrid(rows)[1]).toEqual([{ start: '09:00', end: '12:00' }])
  })

  it('uses the backend 0=Sun..6=Sat convention (Sunday is 0, never 7)', () => {
    const grid = emptyGrid()
    expect(Object.keys(grid).map(Number).sort((a, b) => a - b)).toEqual([0, 1, 2, 3, 4, 5, 6])
    // A Sunday (0) interval is valid + round-trips as day_of_week 0.
    grid[0] = [{ start: '10:00', end: '14:00' }]
    expect(validateGrid(grid)).toBeNull()
    expect(gridToRows(grid)).toEqual([{ day_of_week: 0, start_time: '10:00', end_time: '14:00' }])
  })
})

describe('orderedDays (display order from the clinic first-day setting)', () => {
  it('defaults to Monday-first', () => {
    expect(DEFAULT_FIRST_DAY).toBe(1)
    expect(orderedDays(DEFAULT_FIRST_DAY)).toEqual([1, 2, 3, 4, 5, 6, 0])
  })

  it('Sunday-first wraps to [0..6]', () => {
    expect(orderedDays(0)).toEqual([0, 1, 2, 3, 4, 5, 6])
  })

  it('always returns all seven canonical days exactly once', () => {
    for (const start of [0, 1, 2, 3, 4, 5, 6]) {
      expect([...orderedDays(start)].sort((a, b) => a - b)).toEqual([0, 1, 2, 3, 4, 5, 6])
      expect(orderedDays(start)[0]).toBe(start)
    }
  })
})
