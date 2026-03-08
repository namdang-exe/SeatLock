import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { getBookingHistory, cancelBooking, type BookingSession } from '../api/bookings'
import { getErrorMessage } from '../api/errors'

function canCancel(session: BookingSession): boolean {
  const cutoff = Date.now() + 24 * 60 * 60 * 1000
  return (
    session.bookings.some(b => b.status === 'CONFIRMED') &&
    session.bookings.every(b => b.status !== 'CONFIRMED' || new Date(b.startTime).getTime() > cutoff)
  )
}

function sessionStatus(session: BookingSession): string {
  if (session.bookings.every(b => b.status === 'CANCELLED')) return 'CANCELLED'
  return 'CONFIRMED'
}

export default function BookingsPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data, isLoading, isError } = useQuery({
    queryKey: ['bookings'],
    queryFn: getBookingHistory,
  })

  const [cancellingId, setCancellingId] = useState<string | null>(null)
  const [cancelError, setCancelError] = useState<Record<string, string>>({})

  const handleCancel = async (confirmationNumber: string) => {
    setCancellingId(confirmationNumber)
    setCancelError(prev => ({ ...prev, [confirmationNumber]: '' }))
    try {
      await cancelBooking(confirmationNumber)
      queryClient.invalidateQueries({ queryKey: ['bookings'] })
    } catch (err) {
      setCancelError(prev => ({ ...prev, [confirmationNumber]: getErrorMessage(err) }))
    } finally {
      setCancellingId(null)
    }
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-white shadow px-6 py-4 flex justify-between items-center">
        <div className="flex items-center gap-4">
          <Link to="/venues" className="text-blue-600 hover:underline text-sm">← Venues</Link>
          <h1 className="text-xl font-bold">My Bookings</h1>
        </div>
        <button
          onClick={() => { localStorage.removeItem('token'); navigate('/login') }}
          className="text-sm text-gray-500 hover:text-red-600"
        >
          Sign out
        </button>
      </header>

      <main className="max-w-3xl mx-auto p-6">
        {isLoading && <p className="text-gray-500">Loading bookings…</p>}
        {isError && <p className="text-red-600">Failed to load bookings.</p>}

        {!isLoading && data?.sessions.length === 0 && (
          <div className="text-center py-12">
            <p className="text-gray-500 mb-3">No bookings yet.</p>
            <Link to="/venues" className="text-blue-600 hover:underline">Browse venues</Link>
          </div>
        )}

        <div className="space-y-4">
          {data?.sessions.map(session => {
            const status = sessionStatus(session)
            const eligible = canCancel(session)
            const isCancelling = cancellingId === session.confirmationNumber
            const err = cancelError[session.confirmationNumber]

            return (
              <div key={session.confirmationNumber} className="bg-white rounded-lg shadow p-4">
                <div className="flex justify-between items-start mb-3">
                  <div>
                    <Link
                      to={`/bookings/${session.confirmationNumber}`}
                      className="font-mono font-semibold text-blue-600 hover:underline"
                    >
                      {session.confirmationNumber}
                    </Link>
                    <p className="text-xs text-gray-400 mt-0.5">
                      {new Date(session.createdAt).toLocaleString()}
                    </p>
                  </div>
                  <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${
                    status === 'CONFIRMED'
                      ? 'bg-green-100 text-green-700'
                      : 'bg-gray-200 text-gray-500'
                  }`}>
                    {status}
                  </span>
                </div>

                <div className="space-y-1 mb-3">
                  {session.bookings.map(b => (
                    <div key={b.bookingId} className="flex justify-between text-sm">
                      <span className="text-gray-700">
                        {b.venueName && <span className="font-medium">{b.venueName} · </span>}
                        {new Date(b.startTime).toLocaleString([], {
                          weekday: 'short', month: 'short', day: 'numeric',
                          hour: '2-digit', minute: '2-digit',
                        })}
                      </span>
                      <span className={b.status === 'CANCELLED' ? 'text-gray-400 line-through' : 'text-gray-500'}>
                        {b.status}
                      </span>
                    </div>
                  ))}
                </div>

                {err && <p className="text-red-600 text-xs mb-2">{err}</p>}

                {eligible && (
                  <button
                    onClick={() => handleCancel(session.confirmationNumber)}
                    disabled={isCancelling}
                    className="text-sm text-red-600 hover:text-red-800 disabled:opacity-50"
                  >
                    {isCancelling ? 'Cancelling…' : 'Cancel booking'}
                  </button>
                )}
              </div>
            )
          })}
        </div>
      </main>
    </div>
  )
}
