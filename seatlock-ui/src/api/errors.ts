const ERROR_MESSAGES: Record<string, string> = {
  EMAIL_ALREADY_EXISTS:       'This email is already registered.',
  INVALID_CREDENTIALS:        'Incorrect email or password.',
  SLOT_NOT_AVAILABLE:         'One or more slots are no longer available.',
  SLOT_NOT_FOUND:             'One or more slots could not be found.',
  MISSING_IDEMPOTENCY_KEY:    'Request is missing a required key.',
  SESSION_NOT_FOUND:          'Your hold session has expired.',
  HOLD_EXPIRED:               'Your hold has expired. Please select slots again.',
  HOLD_MISMATCH:              'Hold session mismatch. Please try again.',
  BOOKING_NOT_FOUND:          'Booking not found.',
  CANCELLATION_WINDOW_CLOSED: 'Cancellation is only allowed more than 24 hours before the slot.',
  FORBIDDEN:                  'You do not have permission to perform this action.',
  VALIDATION_ERROR:           'Please check your input and try again.',
  SERVICE_UNAVAILABLE:        'Service is temporarily unavailable. Please try again shortly.',
}

export function getErrorMessage(error: unknown): string {
  if (error && typeof error === 'object' && 'response' in error) {
    const code = (error as { response?: { data?: { error?: string } } })
      .response?.data?.error
    if (code && ERROR_MESSAGES[code]) return ERROR_MESSAGES[code]
  }
  return 'An unexpected error occurred.'
}
