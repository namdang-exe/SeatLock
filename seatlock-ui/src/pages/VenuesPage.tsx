import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { getVenues } from '../api/venues'

export default function VenuesPage() {
  const navigate = useNavigate()
  const { data: venues, isLoading, isError } = useQuery({
    queryKey: ['venues'],
    queryFn: getVenues,
  })

  const handleLogout = () => {
    localStorage.removeItem('token')
    navigate('/login')
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="bg-white shadow px-6 py-4 flex justify-between items-center">
        <h1 className="text-xl font-bold">SeatLock</h1>
        <div className="flex items-center gap-4">
          <Link to="/bookings" className="text-sm text-blue-600 hover:underline">
            My Bookings
          </Link>
          <button
            onClick={handleLogout}
            className="text-sm text-gray-500 hover:text-red-600"
          >
            Sign out
          </button>
        </div>
      </header>

      <main className="max-w-4xl mx-auto p-6">
        <h2 className="text-lg font-semibold mb-4">Available Venues</h2>

        {isLoading && <p className="text-gray-500">Loading venues…</p>}
        {isError && <p className="text-red-600">Failed to load venues.</p>}

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {venues?.map(v => (
            <Link
              key={v.venueId}
              to={`/venues/${v.venueId}/slots`}
              className="bg-white rounded-lg shadow p-4 hover:shadow-md transition-shadow block"
            >
              <h3 className="font-semibold text-lg">{v.name}</h3>
              <p className="text-gray-500 text-sm mt-1">{v.address}</p>
              <p className="text-gray-500 text-sm">{v.city}, {v.state}</p>
            </Link>
          ))}
        </div>

        {!isLoading && venues?.length === 0 && (
          <p className="text-gray-500">No active venues found.</p>
        )}
      </main>
    </div>
  )
}
