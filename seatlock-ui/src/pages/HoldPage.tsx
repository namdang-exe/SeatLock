import { useEffect, useState } from 'react'
import { useParams, useLocation, useNavigate, Link } from 'react-router-dom'
import { confirmBooking } from '../api/bookings'
import { getErrorMessage } from '../api/errors'
import type { Slot } from '../api/venues'

interface HoldState {
  expiresAt: string
  slotDetails: Slot[]
}

function useCountdown(expiresAt: string) {
  const [secondsLeft, setSecondsLeft] = useState(() =>
    Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000))
  )

  useEffect(() => {
    if (secondsLeft <= 0) return
    const id = setInterval(() => {
      const remaining = Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000))
      setSecondsLeft(remaining)
    }, 1000)
    return () => clearInterval(id)
  }, [expiresAt, secondsLeft])

  return secondsLeft
}

function formatCountdown(seconds: number) {
  const m = Math.floor(seconds / 60).toString().padStart(2, '0')
  const s = (seconds % 60).toString().padStart(2, '0')
  return `${m}:${s}`
}

export default function HoldPage() {
  const { sessionId } = useParams<{ sessionId: string }>()
  const location = useLocation()
  const navigate = useNavigate()
  const state = location.state as HoldState | null

  const [error, setError] = useState('')
  const [confirming, setConfirming] = useState(false)

  const secondsLeft = useCountdown(state?.expiresAt ?? new Date().toISOString())
  const expired = secondsLeft <= 0

  if (!state) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <div className="bg-white p-8 rounded-lg shadow text-center">
          <p className="text-gray-600 mb-4">Hold session not found.</p>
          <Link to="/venues" className="text-blue-600 hover:underline">Browse venues</Link>
        </div>
      </div>
    )
  }

  const handleConfirm = async () => {
    setError('')
    setConfirming(true)
    try {
      const res = await confirmBooking(sessionId!)
      navigate(`/bookings/${res.confirmationNumber}`)
    } catch (err) {
      setError(getErrorMessage(err))
    } finally {
      setConfirming(false)
    }
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-white shadow px-6 py-4 flex items-center gap-4">
        <Link to="/venues" className="text-blue-600 hover:underline text-sm">← Venues</Link>
        <h1 className="text-xl font-bold">Confirm your hold</h1>
      </header>

      <main className="max-w-lg mx-auto p-6">
        {/* Countdown */}
        <div className={`text-center mb-6 p-4 rounded-lg ${expired ? 'bg-red-50' : 'bg-blue-50'}`}>
          {expired ? (
            <>
              <p className="text-red-700 font-semibold text-lg">Hold expired</p>
              <p className="text-red-600 text-sm mt-1">
                Your hold has timed out.{' '}
                <Link to="/venues" className="underline">Browse venues to start again.</Link>
              </p>
            </>
          ) : (
            <>
              <p className="text-blue-700 text-sm font-medium">Hold expires in</p>
              <p className={`text-4xl font-mono font-bold mt-1 ${
                secondsLeft < 120 ? 'text-red-600' : 'text-blue-800'
              }`}>
                {formatCountdown(secondsLeft)}
              </p>
            </>
          )}
        </div>

        {/* Slot list */}
        <div className="bg-white rounded-lg shadow divide-y mb-6">
          {state.slotDetails.map(slot => (
            <div key={slot.slotId} className="px-4 py-3">
              <p className="font-medium text-sm">
                {new Date(slot.startTime).toLocaleDateString([], {
                  weekday: 'short', month: 'short', day: 'numeric',
                })}
                {' — '}
                {new Date(slot.startTime).toLocaleTimeString([], {
                  hour: '2-digit', minute: '2-digit',
                })}
                {' – '}
                {new Date(slot.endTime).toLocaleTimeString([], {
                  hour: '2-digit', minute: '2-digit',
                })}
              </p>
            </div>
          ))}
        </div>

        {error && <p className="text-red-600 text-sm mb-4">{error}</p>}

        <button
          onClick={handleConfirm}
          disabled={expired || confirming}
          className="w-full bg-blue-600 text-white py-3 rounded-lg font-medium hover:bg-blue-700 disabled:opacity-50"
        >
          {confirming ? 'Confirming…' : 'Confirm Booking'}
        </button>
      </main>
    </div>
  )
}
