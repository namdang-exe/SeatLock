import client from './client'

export interface Venue {
  venueId: string
  name: string
  address: string
  city: string
  state: string
  status: string
}

export interface Slot {
  slotId: string
  venueId: string
  startTime: string
  endTime: string
  status: string
}

export interface HoldResponse {
  sessionId: string
  expiresAt: string
  holds: { holdId: string; slotId: string }[]
}

export const getVenues = () =>
  client.get<Venue[]>('/venues').then(r => r.data)

export const getSlots = (venueId: string, date: string) =>
  client.get<Slot[]>(`/venues/${venueId}/slots`, { params: { date } }).then(r => r.data)

export const createHold = (slotIds: string[]) =>
  client.post<HoldResponse>(
    '/holds',
    { slotIds },
    { headers: { 'Idempotency-Key': crypto.randomUUID() } }
  ).then(r => r.data)
