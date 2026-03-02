package com.seatlock.venue.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatlock.venue.dto.SlotResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlotCacheServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private SlotCacheService slotCacheService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        slotCacheService = new SlotCacheService(redis, objectMapper);
        ReflectionTestUtils.setField(slotCacheService, "ttlSeconds", 5L);

        Mockito.lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void buildKey_formatsCorrectly() {
        UUID venueId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        LocalDate date = LocalDate.of(2024, 3, 15);

        String key = slotCacheService.buildKey(venueId, date);

        assertThat(key).isEqualTo("slots:11111111-1111-1111-1111-111111111111:2024-03-15");
    }

    @Test
    void get_onMissingKey_returnsEmpty() {
        when(valueOps.get("slots:missing:2024-03-15")).thenReturn(null);

        Optional<List<SlotResponse>> result = slotCacheService.get("slots:missing:2024-03-15");

        assertThat(result).isEmpty();
    }

    @Test
    void put_then_get_roundTripPreservesAllFields() {
        UUID slotId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();
        Instant start = Instant.parse("2024-03-15T09:00:00Z");
        Instant end = Instant.parse("2024-03-15T10:00:00Z");
        SlotResponse original = new SlotResponse(slotId.toString(), venueId.toString(), start, end, "AVAILABLE");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);

        slotCacheService.put("slots:test:2024-03-15", List.of(original));

        verify(valueOps).set(eq("slots:test:2024-03-15"), jsonCaptor.capture(), eq(Duration.ofSeconds(5)));

        when(valueOps.get("slots:test:2024-03-15")).thenReturn(jsonCaptor.getValue());
        Optional<List<SlotResponse>> result = slotCacheService.get("slots:test:2024-03-15");

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        SlotResponse retrieved = result.get().get(0);
        assertThat(retrieved.slotId()).isEqualTo(original.slotId());
        assertThat(retrieved.venueId()).isEqualTo(original.venueId());
        assertThat(retrieved.startTime()).isEqualTo(original.startTime());
        assertThat(retrieved.endTime()).isEqualTo(original.endTime());
        assertThat(retrieved.status()).isEqualTo(original.status());
    }

    @Test
    void put_storesWithConfiguredTtl() {
        SlotResponse slot = new SlotResponse(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                Instant.parse("2024-03-15T09:00:00Z"), Instant.parse("2024-03-15T10:00:00Z"), "AVAILABLE");

        slotCacheService.put("slots:test:2024-03-15", List.of(slot));

        verify(valueOps).set(anyString(), anyString(), eq(Duration.ofSeconds(5)));
    }

    @Test
    void get_onCorruptJson_returnsEmpty() {
        when(valueOps.get("slots:corrupt:2024-03-15")).thenReturn("not-valid-json");

        Optional<List<SlotResponse>> result = slotCacheService.get("slots:corrupt:2024-03-15");

        assertThat(result).isEmpty();
    }
}
