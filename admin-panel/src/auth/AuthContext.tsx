import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react'
import { ApiClient } from '../api/client'
import type { Role } from '../api/types'

interface AuthValue {
  /** True while a bearer token is held (the user is logged in). */
  isAuthenticated: boolean
  /** The authenticated user's role, or null when logged out. Drives read-only UI (FR-FXJEVE). */
  role: Role | null
  login: (clinicSlug: string, email: string, password: string) => Promise<void>
  logout: () => void
  /** The shared API client; auto-attaches the token and clears it on 401. */
  api: ApiClient
}

const AuthContext = createContext<AuthValue | null>(null)

// localStorage keys for the persisted session. The token is persisted so a page
// refresh / new tab / browser restart doesn't force a re-login (it survives until
// the server-side token TTL expires or the user logs out). A stale/expired token is
// self-healing: the first API call 401s → onUnauthorized clears it → back to login.
const TOKEN_KEY = 'symptree.admin.token'
const ROLE_KEY = 'symptree.admin.role'

/** Best-effort localStorage read (guards against disabled storage). */
function readStored(key: string): string | null {
  try {
    return typeof localStorage !== 'undefined' ? localStorage.getItem(key) : null
  } catch {
    return null
  }
}

/**
 * Holds the bearer token and persists it in localStorage so the session survives a
 * refresh/restart, wires it into the shared {@link ApiClient}, and clears both the
 * in-memory and persisted copies on logout or any 401.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(() => readStored(TOKEN_KEY))
  const [role, setRole] = useState<Role | null>(() => readStored(ROLE_KEY) as Role | null)
  // A ref mirrors the token so the (stable) client closure always reads the latest.
  const tokenRef = useRef<string | null>(token)
  tokenRef.current = token

  // Clears both the in-memory session and its persisted copy.
  const clearSession = useCallback(() => {
    tokenRef.current = null
    setToken(null)
    setRole(null)
    try {
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(ROLE_KEY)
    } catch {
      /* storage unavailable — the in-memory clear already happened */
    }
  }, [])

  const api = useMemo(
    () =>
      new ApiClient({
        getToken: () => tokenRef.current,
        onUnauthorized: clearSession,
      }),
    [clearSession],
  )

  const login = useCallback(
    async (clinicSlug: string, email: string, password: string) => {
      const res = await api.login(clinicSlug, email, password)
      tokenRef.current = res.token
      setToken(res.token)
      setRole(res.role)
      try {
        localStorage.setItem(TOKEN_KEY, res.token)
        localStorage.setItem(ROLE_KEY, res.role)
      } catch {
        /* storage unavailable — session still works in-memory for this tab */
      }
    },
    [api],
  )

  const logout = clearSession

  const value = useMemo<AuthValue>(
    () => ({ isAuthenticated: token !== null, role, login, logout, api }),
    [token, role, login, logout, api],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within an AuthProvider')
  return ctx
}
