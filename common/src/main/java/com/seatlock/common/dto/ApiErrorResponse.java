package com.seatlock.common.dto;

public class ApiErrorResponse {

    private final String error;
    private final String message;

    public ApiErrorResponse(String error, String message) {
        this.error = error;
        this.message = message;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }
}
