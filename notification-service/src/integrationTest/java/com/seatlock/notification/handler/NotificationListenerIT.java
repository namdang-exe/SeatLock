package com.seatlock.notification.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatlock.notification.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class NotificationListenerIT extends AbstractIntegrationTest {

    @Autowired
    private SqsAsyncClient sqsClient;

    @Value("${seatlock.notifications.queue-name}")
    private String queueName;

    private String mailpitApiUrl;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private final RestTemplate restTemplate = new RestTemplate();

    @BeforeEach
    void purgeMailpit() {
        mailpitApiUrl = "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025);
        try {
            restTemplate.delete(mailpitApiUrl + "/api/v1/messages");
        } catch (Exception ignored) {
            // Mailpit may have no messages yet
        }
    }

    @Test
    void bookingConfirmedEventTriggersEmail() throws Exception {
        String confirmationNumber = "SL-TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String body = buildEnvelope("BookingConfirmed", Map.of(
                "confirmationNumber", confirmationNumber,
                "sessionId", UUID.randomUUID().toString(),
                "userId", UUID.randomUUID().toString(),
                "slotIds", List.of(UUID.randomUUID().toString()),
                "timestamp", Instant.now().toString()));

        sendToQueue(body);

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> response = restTemplate.getForObject(
                            mailpitApiUrl + "/api/v1/messages", Map.class);
                    assertThat(response).isNotNull();
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> messages =
                            (List<Map<String, Object>>) response.get("messages");
                    assertThat(messages).isNotNull().isNotEmpty();
                    boolean found = messages.stream()
                            .anyMatch(m -> ((String) m.get("Subject")).contains(confirmationNumber));
                    assertThat(found)
                            .as("Expected email with subject containing '%s'", confirmationNumber)
                            .isTrue();
                });
    }

    @Test
    void bookingCancelledEventTriggersEmail() throws Exception {
        String confirmationNumber = "SL-TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String body = buildEnvelope("BookingCancelled", Map.of(
                "confirmationNumber", confirmationNumber,
                "sessionId", UUID.randomUUID().toString(),
                "userId", UUID.randomUUID().toString(),
                "cancelledSlotIds", List.of(UUID.randomUUID().toString()),
                "timestamp", Instant.now().toString()));

        sendToQueue(body);

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> response = restTemplate.getForObject(
                            mailpitApiUrl + "/api/v1/messages", Map.class);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> messages =
                            (List<Map<String, Object>>) response.get("messages");
                    assertThat(messages).isNotNull().isNotEmpty();
                    boolean found = messages.stream()
                            .anyMatch(m -> ((String) m.get("Subject")).contains(confirmationNumber));
                    assertThat(found).isTrue();
                });
    }

    @Test
    void holdExpiredEventTriggersEmail() throws Exception {
        String body = buildEnvelope("HoldExpired", Map.of(
                "sessionId", UUID.randomUUID().toString(),
                "userId", UUID.randomUUID().toString(),
                "expiredSlotIds", List.of(UUID.randomUUID().toString()),
                "timestamp", Instant.now().toString()));

        sendToQueue(body);

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> response = restTemplate.getForObject(
                            mailpitApiUrl + "/api/v1/messages", Map.class);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> messages =
                            (List<Map<String, Object>>) response.get("messages");
                    assertThat(messages).isNotNull().isNotEmpty();
                    boolean found = messages.stream()
                            .anyMatch(m -> "Your hold has expired".equals(m.get("Subject")));
                    assertThat(found).isTrue();
                });
    }

    private void sendToQueue(String body) throws Exception {
        String queueUrl = "http://" + ELASTICMQ.getHost() + ":" + ELASTICMQ.getMappedPort(9324)
                + "/000000000000/" + queueName;
        sqsClient.sendMessage(req -> req.queueUrl(queueUrl).messageBody(body)).get();
    }

    private String buildEnvelope(String type, Object payload) throws Exception {
        return objectMapper.writeValueAsString(Map.of("type", type, "payload", payload));
    }
}
