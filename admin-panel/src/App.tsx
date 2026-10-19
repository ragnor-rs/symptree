import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import { Layout } from './components/Layout'
import { LoginPage } from './pages/LoginPage'
import { DoctorsPage } from './pages/DoctorsPage'
import { ScheduleEditor } from './pages/ScheduleEditor'
import { SessionsPage } from './pages/SessionsPage'
import { SessionDetailPage } from './pages/SessionDetailPage'
import { BookingsPage } from './pages/BookingsPage'
import { SettingsPage } from './pages/SettingsPage'

/** Top-level routing. Unauthenticated → login; authenticated → the section shell. */
export function App() {
  const { isAuthenticated } = useAuth()
  if (!isAuthenticated) return <LoginPage />

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Navigate to="/doctors" replace />} />
        <Route path="/doctors" element={<DoctorsPage />} />
        <Route path="/doctors/:id/schedule" element={<ScheduleEditor />} />
        <Route path="/sessions" element={<SessionsPage />} />
        <Route path="/sessions/:id" element={<SessionDetailPage />} />
        <Route path="/bookings" element={<BookingsPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="*" element={<Navigate to="/doctors" replace />} />
      </Route>
    </Routes>
  )
}
