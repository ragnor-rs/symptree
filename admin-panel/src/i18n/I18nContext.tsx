import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { LANGS, STRINGS, type Lang, type StringKey } from './strings'

interface I18nValue {
  lang: Lang
  setLang: (lang: Lang) => void
  /** Localize a chrome string by its catalog key. */
  t: (key: StringKey) => string
  /**
   * Localize a semantic code under `<prefix>.<code>` (e.g. urgency, clinic_action).
   * Codes with no catalog entry — open-ended specialist codes authored in the
   * algorithm content — fall back to the raw code rather than a placeholder.
   */
  tCode: (prefix: string, code: string) => string
}

const STORAGE_KEY = 'symptree.admin.lang'

const I18nContext = createContext<I18nValue | null>(null)

function initialLang(): Lang {
  const stored = typeof localStorage !== 'undefined' ? localStorage.getItem(STORAGE_KEY) : null
  return (LANGS as string[]).includes(stored ?? '') ? (stored as Lang) : 'ru'
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(initialLang)

  const setLang = useCallback((next: Lang) => {
    setLangState(next)
    if (typeof localStorage !== 'undefined') localStorage.setItem(STORAGE_KEY, next)
  }, [])

  const value = useMemo<I18nValue>(() => {
    const table = STRINGS[lang]
    return {
      lang,
      setLang,
      t: (key) => table[key] ?? key,
      tCode: (prefix, code) => {
        const key = `${prefix}.${code}` as StringKey
        return table[key] ?? code
      },
    }
  }, [lang, setLang])

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>
}

export function useI18n(): I18nValue {
  const ctx = useContext(I18nContext)
  if (!ctx) throw new Error('useI18n must be used within an I18nProvider')
  return ctx
}
