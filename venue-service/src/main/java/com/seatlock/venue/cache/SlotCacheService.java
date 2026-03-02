package com.seatlock.venue.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatlock.venue.dto.SlotResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SlotCacheService {

    private static final Logger log = LoggerFactory.getLogger(SlotCacheService.class);
    private static final String KEY_PREFIX = "slots:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${seatlock.cache.slots-ttl-seconds:5}")
    private long ttlSeconds;

    public SlotCacheService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public String buildKey(UUID venueId, LocalDate date) {
        return KEY_PREFIX + venueId + ":" + date;
    }

    public Optional<List<SlotResponse>> get(String key) {
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            List<SlotResponse> slots = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, SlotResponse.class));
            return Optional.of(slots);
        } catch (JsonProcessingException e) {
            log.warn("Cache deserialization failed for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String key, List<SlotResponse> slots) {
        try {
            String json = objectMapper.writeValueAsString(slots);
            redis.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
        } catch (JsonProcessingException e) {
            log.warn("Cache serialization failed for key {}: {}", key, e.getMessage());
        }
    }
}
