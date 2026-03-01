package com.seatlock.user.exception;

import com.seatlock.common.dto.ApiErrorResponse;
import com.seatlock.common.exception.EmailAlreadyExistsException;
import com.seatlock.common.exception.InvalidCredentialsException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EmailAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        return new ApiErrorResponse("EMAIL_ALREADY_EXISTS", ex.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiErrorResponse handleInvalidCredentials() {
        return new ApiErrorResponse("INVALID_CREDENTIALS", "Invalid email or password");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return new ApiErrorResponse("VALIDATION_ERROR", message);
    }
}