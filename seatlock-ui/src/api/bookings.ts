import client from './client'

export interface BookingResponse {
  confirmationNumber: string
  sessionId: string
  bookings: { bookingId: string; slotId: string; status: string }[]
}

export interface BookingSession {
  confirmationNumber: string
  sessionId: string
  createdAt: string
  bookings: BookingSlot[]
}

export interface BookingSlot {
  bookingId: string
  slotId: string
  startTime: string
  venueName: string
  status: string
  cancelledAt: string | null
}

export interface BookingHistoryResponse {
  sessions: BookingSession[]
}

export interface CancelResponse {
  confirmationNumber: string
  cancelledAt: string
  bookings: { bookingId: string; slotId: string; status: string }[]
}

export const confirmBooking = (sessionId: string) =>
  client.post<BookingResponse>('/bookings', { sessionId }).then(r => r.data)

export const getBookingHistory = () =>
  client.get<BookingHistoryResponse>('/bookings').then(r => r.data)

export const cancelBooking = (confirmationNumber: string) =>
  client.post<CancelResponse>(`/bookings/${confirmationNumber}/cancel`).then(r => r.data)
