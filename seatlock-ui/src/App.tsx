import { Routes, Route, Navigate } from 'react-router-dom'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'
import VenuesPage from './pages/VenuesPage'
import SlotsPage from './pages/SlotsPage'
import HoldPage from './pages/HoldPage'
import BookingsPage from './pages/BookingsPage'
import BookingDetailPage from './pages/BookingDetailPage'

function PrivateRoute({ children }: { children: React.ReactNode }) {
  const token = localStorage.getItem('token')
  return token ? <>{children}</> : <Navigate to="/login" replace />
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/venues" element={<PrivateRoute><VenuesPage /></PrivateRoute>} />
      <Route path="/venues/:venueId/slots" element={<PrivateRoute><SlotsPage /></PrivateRoute>} />
      <Route path="/holds/:sessionId" element={<PrivateRoute><HoldPage /></PrivateRoute>} />
      <Route path="/bookings" element={<PrivateRoute><BookingsPage /></PrivateRoute>} />
      <Route path="/bookings/:confirmationNumber" element={<PrivateRoute><BookingDetailPage /></PrivateRoute>} />
      <Route path="*" element={<Navigate to="/venues" replace />} />
    </Routes>
  )
}
