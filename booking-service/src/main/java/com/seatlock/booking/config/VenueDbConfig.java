package com.seatlock.booking.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Second datasource wired to venue_db.
 * Used exclusively for slot status writes (HELD/BOOKED/AVAILABLE) so that
 * venue-service's availability queries read the correct status on cache misses.
 *
 * This is the Phase 1 fix for the cross-DB write gap documented in BUGS.md.
 * All writes via this JdbcTemplate are best-effort — failures are logged and
 * swallowed because Redis SETNX is the true double-booking gate.
 */
@Configuration
public class VenueDbConfig {

    @Bean("venueJdbcTemplate")
    public JdbcTemplate venueJdbcTemplate(
            @Value("${seatlock.venue-datasource.url}") String url,
            @Value("${seatlock.venue-datasource.username}") String username,
            @Value("${seatlock.venue-datasource.password}") String password) {
        DataSource ds = DataSourceBuilder.create()
                .url(url).username(username).password(password).build();
        return new JdbcTemplate(ds);
    }
}
