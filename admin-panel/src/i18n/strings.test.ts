import { describe, expect, it } from 'vitest'
import { STRINGS } from './strings'

// Mirrors the backend MessageCatalogConsistencyTest (AC-XKH348.7): the ru and kk
// bundles must define exactly the same key set, and no value may be blank.
describe('i18n catalog parity', () => {
  const ruKeys = Object.keys(STRINGS.ru).sort()
  const kkKeys = Object.keys(STRINGS.kk).sort()

  it('ru and kk define the identical key set', () => {
    expect(kkKeys).toEqual(ruKeys)
  })

  it('every key in ru exists in kk and vice versa', () => {
    for (const key of ruKeys) expect(STRINGS.kk).toHaveProperty(key)
    for (const key of kkKeys) expect(STRINGS.ru).toHaveProperty(key)
  })

  it('no translation is blank', () => {
    for (const [key, value] of Object.entries(STRINGS.ru)) {
      expect(value.trim(), `ru.${key}`).not.toBe('')
    }
    for (const [key, value] of Object.entries(STRINGS.kk)) {
      expect(value.trim(), `kk.${key}`).not.toBe('')
    }
  })
})
