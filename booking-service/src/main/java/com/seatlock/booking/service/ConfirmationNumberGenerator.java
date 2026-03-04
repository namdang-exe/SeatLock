package com.seatlock.booking.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Random;

@Component
public class ConfirmationNumberGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final Random random = new Random();

    public String generate() {
        String date = LocalDate.now(ZoneOffset.UTC).format(DATE_FORMAT);
        String suffix = String.format("%04d", random.nextInt(10000));
        return "SL-" + date + "-" + suffix;
    }
}
