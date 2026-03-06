package com.seatlock.notification.event;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Typed envelope wrapping all booking lifecycle events published to SQS.
 * Format: {"type": "BookingConfirmed", "payload": {...}}
 */
public record EventEnvelope(String type, JsonNode payload) {
}
