package com.stefankabasaj.courtly.event;

public enum BookingEventType {
    BOOKING_CREATED,    // PENDING, waiting for payment
    BOOKING_CONFIRMED,  // paid
    BOOKING_CANCELLED,  // by the user or an admin
    BOOKING_EXPIRED     // not paid in time
}
