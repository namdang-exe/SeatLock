import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getSlots, createHold } from '../api/venues'
import { getErrorMessage } from '../api/errors'

const STATUS_CLASSES: Record<string, string> = {
  AVAILABLE: 'bg-green-100 text-green-800 hover:bg-green-200 cursor-pointer',
  HELD:      'bg-yellow-100 text-yellow-700 cursor-not-allowed opacity-70',
  BOOKED:    'bg-gray-200 text-gray-500 cursor-not-allowed opacity-60',
}

function todayDate() {
  return new Date().toISOString().split('T')[0]
}

export default function SlotsPage() {
  const { venueId } = useParams<{ venueId: string }>()
  const navigate = useNavigate()
  const [date, setDate] = useState(todayDate)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [holdError, setHoldError] = useState('')
  const [holding, setHolding] = useState(false)

  const { data: slots, isLoading, isError } = useQuery({
    queryKey: ['slots', venueId, date],
    queryFn: () => getSlots(venueId!, date),
    refetchInterval: 5000,
    enabled: !!venueId,
  })

  const toggleSlot = (slotId: string, status: string) => {
    if (status !== 'AVAILABLE') return
    setSelected(prev => {
      const next = new Set(prev)
      next.has(slotId) ? next.delete(slotId) : next.add(slotId)
      return next
    })
  }

  const handleHold = async () => {
    if (selected.size === 0) return
    setHoldError('')
    setHolding(true)
    try {
      const res = await createHold([...selected])
      const selectedSlotDetails = slots?.filter(s => selected.has(s.slotId)) ?? []
      navigate(`/holds/${res.sessionId}`, {
        state: { expiresAt: res.expiresAt, slotDetails: selectedSlotDetails },
      })
    } catch (err) {
      setHoldError(getErrorMessage(err))
    } finally {
      setHolding(false)
    }
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-white shadow px-6 py-4 flex items-center gap-4">
        <button
          onClick={() => navigate('/venues')}
          className="text-blue-600 hover:underline text-sm"
        >
          ← Venues
        </button>
        <h1 className="text-xl font-bold">Slots</h1>
      </header>

      <main className="max-w-3xl mx-auto p-6">
        <div className="flex items-center gap-4 mb-6">
          <label className="font-medium text-sm text-gray-700">Date</label>
          <input
            type="date"
            value={date}
            onChange={e => {
              setDate(e.target.value)
              setSelected(new Set())
            }}
            className="border rounded px-3 py-1 text-sm"
          />
          <span className="ml-auto text-xs text-gray-400">Auto-refreshes every 5s</span>
        </div>

        {isLoading && <p className="text-gray-500">Loading slots…</p>}
        {isError && <p className="text-red-600">Failed to load slots.</p>}

        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-3">
          {slots?.map(slot => {
            const isSelected = selected.has(slot.slotId)
            const time = new Date(slot.startTime).toLocaleTimeString([], {
              hour: '2-digit',
              minute: '2-digit',
            })
            return (
              <button
                key={slot.slotId}
                onClick={() => toggleSlot(slot.slotId, slot.status)}
                disabled={slot.status !== 'AVAILABLE'}
                className={`rounded-lg p-3 text-left border-2 transition-all ${
                  isSelected ? 'border-blue-500 ring-2 ring-blue-200' : 'border-transparent'
                } ${STATUS_CLASSES[slot.status] ?? 'bg-gray-100 text-gray-500'}`}
              >
                <p className="font-semibold text-sm">{time}</p>
                <p className="text-xs mt-0.5 capitalize">{slot.status.toLowerCase()}</p>
              </button>
            )
          })}
        </div>

        {!isLoading && slots?.length === 0 && (
          <p className="text-gray-500 mt-4">No slots for this date.</p>
        )}

        {holdError && <p className="mt-4 text-red-600 text-sm">{holdError}</p>}

        {selected.size > 0 && (
          <div className="mt-6 flex items-center gap-4">
            <span className="text-sm text-gray-600">
              {selected.size} slot{selected.size > 1 ? 's' : ''} selected
            </span>
            <button
              onClick={handleHold}
              disabled={holding}
              className="bg-blue-600 text-white px-4 py-2 rounded hover:bg-blue-700 disabled:opacity-50 text-sm"
            >
              {holding ? 'Placing hold…' : 'Hold selected'}
            </button>
          </div>
        )}
      </main>
    </div>
  )
}
