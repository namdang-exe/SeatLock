package com.seatlock.booking.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatlock.booking.exception.RedisUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.UUID;

@Repository
public class RedisHoldRepository {

    static final String HOLD_KEY_PREFIX = "hold:";
    static final String SLOT_CACHE_KEY_PREFIX = "slots:";
    static final Duration HOLD_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisHoldRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Atomically set hold:{slotId} if absent (Redis SETNX with TTL).
     *
     * @return true if the key was set (hold acquired), false if the key already existed
     * @throws RedisUnavailableException if Redis is unreachable
     */
    public boolean setnx(UUID slotId, HoldPayload payload) {
        String key = HOLD_KEY_PREFIX + slotId;
        String value;
        try {
            value = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize hold payload", e);
        }
        try {
            Boolean result = redisTemplate.opsForValue().setIfAbsent(key, value, HOLD_TTL);
            return Boolean.TRUE.equals(result);
        } catch (DataAccessException e) {
            throw new RedisUnavailableException(e);
        }
    }

    /**
     * Delete hold:{slotId}. Safe to call when the key does not exist.
     */
    public void del(UUID slotId) {
        try {
            redisTemplate.delete(HOLD_KEY_PREFIX + slotId);
        } catch (Exception ignored) {
            // Best-effort cleanup; TTL will self-heal within 30 min
        }
    }

    /**
     * Delete slots:{venueId}:{date} cache key. Non-fatal — TTL cleans up within 5s.
     */
    public void deleteSlotCache(UUID venueId, String date) {
        try {
            redisTemplate.delete(SLOT_CACHE_KEY_PREFIX + venueId + ":" + date);
        } catch (Exception ignored) {
            // Non-fatal cache invalidation; 5s TTL is the safety net
        }
    }

    /** Exposed for tests — returns the raw Redis value for a hold key. */
    public String getRawHold(UUID slotId) {
        return redisTemplate.opsForValue().get(HOLD_KEY_PREFIX + slotId);
    }

    /** Exposed for tests — returns the remaining TTL in seconds (-2 if key absent). */
    public Long getTtlSeconds(UUID slotId) {
        return redisTemplate.getExpire(HOLD_KEY_PREFIX + slotId);
    }
}
