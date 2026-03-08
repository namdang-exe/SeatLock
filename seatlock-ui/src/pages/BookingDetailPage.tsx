import { useState } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { getBookingHistory, cancelBooking, type BookingSession } from '../api/bookings'
import { getErrorMessage } from '../api/errors'

function canCancel(session: BookingSession): boolean {
  const cutoff = Date.now() + 24 * 60 * 60 * 1000
  return (
    session.bookings.some(b => b.status === 'CONFIRMED') &&
    session.bookings.every(b => b.status !== 'CONFIRMED' || new Date(b.startTime).getTime() > cutoff)
  )
}

export default function BookingDetailPage() {
  const { confirmationNumber } = useParams<{ confirmationNumber: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const { data, isLoading, isError } = useQuery({
    queryKey: ['bookings'],
    queryFn: getBookingHistory,
  })

  const [cancelling, setCancelling] = useState(false)
  const [cancelError, setCancelError] = useState('')

  const session = data?.sessions.find(s => s.confirmationNumber === confirmationNumber)
  const eligible = session ? canCancel(session) : false
  const allCancelled = session?.bookings.every(b => b.status === 'CANCELLED')

  const handleCancel = async () => {
    if (!confirmationNumber) return
    setCancelError('')
    setCancelling(true)
    try {
      await cancelBooking(confirmationNumber)
      queryClient.invalidateQueries({ queryKey: ['bookings'] })
    } catch (err) {
      setCancelError(getErrorMessage(err))
    } finally {
      setCancelling(false)
    }
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-white shadow px-6 py-4 flex items-center gap-4">
        <Link to="/bookings" className="text-blue-600 hover:underline text-sm">← My Bookings</Link>
        <h1 className="text-xl font-bold">Booking Detail</h1>
      </header>

      <main className="max-w-lg mx-auto p-6">
        {isLoading && <p className="text-gray-500">Loading…</p>}
        {isError && <p className="text-red-600">Failed to load booking.</p>}

        {!isLoading && !session && (
          <div className="text-center py-12">
            <p className="text-gray-500 mb-3">Booking not found.</p>
            <Link to="/bookings" className="text-blue-600 hover:underline">Back to bookings</Link>
          </div>
        )}

        {session && (
          <div className="bg-white rounded-lg shadow p-5">
            <div className="flex justify-between items-start mb-4">
              <div>
                <p className="text-xs text-gray-400 uppercase tracking-wide">Confirmation</p>
                <p className="font-mono font-bold text-lg">{session.confirmationNumber}</p>
              </div>
              <span className={`text-xs font-medium px-2 py-1 rounded-full ${
                allCancelled ? 'bg-gray-200 text-gray-500' : 'bg-green-100 text-green-700'
              }`}>
                {allCancelled ? 'CANCELLED' : 'CONFIRMED'}
              </span>
            </div>

            <p className="text-xs text-gray-400 mb-4">
              Booked on {new Date(session.createdAt).toLocaleString()}
            </p>

            <div className="divide-y border rounded-lg mb-5">
              {session.bookings.map(b => (
                <div key={b.bookingId} className="px-4 py-3">
                  <div className="flex justify-between items-center">
                    <div>
                      {b.venueName && (
                        <p className="font-medium text-sm">{b.venueName}</p>
                      )}
                      <p className="text-sm text-gray-600">
                        {new Date(b.startTime).toLocaleString([], {
                          weekday: 'long', month: 'long', day: 'numeric',
                          hour: '2-digit', minute: '2-digit',
                        })}
                      </p>
                    </div>
                    <span className={`text-xs ${
                      b.status === 'CANCELLED' ? 'text-gray-400' : 'text-green-600'
                    }`}>
                      {b.status}
                    </span>
                  </div>
                  {b.cancelledAt && (
                    <p className="text-xs text-gray-400 mt-1">
                      Cancelled {new Date(b.cancelledAt).toLocaleString()}
                    </p>
                  )}
                </div>
              ))}
            </div>

            {cancelError && <p className="text-red-600 text-sm mb-3">{cancelError}</p>}

            {eligible && (
              <button
                onClick={handleCancel}
                disabled={cancelling}
                className="w-full border border-red-400 text-red-600 py-2 rounded hover:bg-red-50 disabled:opacity-50 text-sm font-medium"
              >
                {cancelling ? 'Cancelling…' : 'Cancel booking'}
              </button>
            )}

            {!eligible && !allCancelled && (
              <p className="text-xs text-gray-400 text-center">
                Cancellation is only available more than 24 hours before the slot.
              </p>
            )}

            <button
              onClick={() => navigate('/venues')}
              className="w-full mt-3 text-sm text-blue-600 hover:underline"
            >
              Browse more venues
            </button>
          </div>
        )}
      </main>
    </div>
  )
}
