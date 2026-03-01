package com.seatlock.user;

import com.seatlock.common.exception.BookingNotFoundException;
import com.seatlock.common.exception.CancellationWindowClosedException;
import com.seatlock.common.exception.EmailAlreadyExistsException;
import com.seatlock.common.exception.HoldExpiredException;
import com.seatlock.common.exception.HoldMismatchException;
import com.seatlock.common.exception.InvalidCredentialsException;
import com.seatlock.common.exception.MissingIdempotencyKeyException;
import com.seatlock.common.exception.ServiceUnavailableException;
import com.seatlock.common.exception.SessionNotFoundException;
import com.seatlock.common.exception.SlotNotAvailableException;
import com.seatlock.common.exception.SlotNotFoundException;
import com.seatlock.common.exception.VenueNotFoundException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DomainExceptionTest {

    @Test
    void slotNotAvailable_hasCorrectCodeAndStatus() {
        var ex = new SlotNotAvailableException();
        assertThat(ex.getErrorCode()).isEqualTo("SLOT_NOT_AVAILABLE");
        assertThat(ex.getHttpStatus()).isEqualTo(409);
        assertThat(ex.getMessage()).isNotBlank();
    }

    @Test
    void holdExpired_hasCorrectCodeAndStatus() {
        var ex = new HoldExpiredException();
        assertThat(ex.getErrorCode()).isEqualTo("HOLD_EXPIRED");
        assertThat(ex.getHttpStatus()).isEqualTo(409);
    }

    @Test
    void holdMismatch_hasCorrectCodeAndStatus() {
        var ex = new HoldMismatchException();
        assertThat(ex.getErrorCode()).isEqualTo("HOLD_MISMATCH");
        assertThat(ex.getHttpStatus()).isEqualTo(409);
    }

    @Test
    void cancellationWindowClosed_hasCorrectCodeAndStatus() {
        var ex = new CancellationWindowClosedException();
        assertThat(ex.getErrorCode()).isEqualTo("CANCELLATION_WINDOW_CLOSED");
        assertThat(ex.getHttpStatus()).isEqualTo(409);
    }

    @Test
    void notFoundExceptions_haveStatus404() {
        assertThat(new SlotNotFoundException().getHttpStatus()).isEqualTo(404);
        assertThat(new VenueNotFoundException().getHttpStatus()).isEqualTo(404);
        assertThat(new SessionNotFoundException().getHttpStatus()).isEqualTo(404);
        assertThat(new BookingNotFoundException().getHttpStatus()).isEqualTo(404);
    }

    @Test
    void authExceptions_haveCorrectStatuses() {
        assertThat(new EmailAlreadyExistsException().getHttpStatus()).isEqualTo(409);
        assertThat(new InvalidCredentialsException().getHttpStatus()).isEqualTo(401);
    }

    @Test
    void otherExceptions_haveCorrectStatuses() {
        assertThat(new MissingIdempotencyKeyException().getHttpStatus()).isEqualTo(400);
        assertThat(new ServiceUnavailableException().getHttpStatus()).isEqualTo(503);
    }

    @Test
    void customMessage_overridesDefault() {
        var ex = new SlotNotAvailableException("Slot 42 is gone");
        assertThat(ex.getMessage()).isEqualTo("Slot 42 is gone");
        assertThat(ex.getErrorCode()).isEqualTo("SLOT_NOT_AVAILABLE");
    }
}
