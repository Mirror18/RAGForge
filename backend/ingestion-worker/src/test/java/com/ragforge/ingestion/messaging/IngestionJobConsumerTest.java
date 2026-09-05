package com.ragforge.ingestion.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionJobConsumerTest {
    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private RabbitTemplate rabbitTemplate;
    private RabbitMessagingProperties properties;
    private JdbcIngestionIdempotencyStore idempotencyStore;
    private IngestionSideEffectHandler handler;
    private Channel channel;
    private IngestionJobConsumer consumer;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        properties = new RabbitMessagingProperties();
        idempotencyStore = mock(JdbcIngestionIdempotencyStore.class);
        handler = mock(IngestionSideEffectHandler.class);
        channel = mock(Channel.class);
        consumer = new IngestionJobConsumer(objectMapper, rabbitTemplate, properties, idempotencyStore, handler);
    }

    @Test
    void acknowledgesOnlyAfterDurableProcessorReturns() throws Exception {
        when(idempotencyStore.process(any(), any(), anyString(), anyString(), any()))
                .thenReturn(JdbcIngestionIdempotencyStore.ProcessResult.PROCESSED);

        consumer.onMessage(message(1, 1, false), channel);

        verify(idempotencyStore).process(any(), any(), anyString(), anyString(), any());
        verify(channel).basicAck(1, false);
    }

    @Test
    void retryableFailureIsRepublishedToTtlRetryQueueAndOriginalIsAcked() throws Exception {
        when(idempotencyStore.process(any(), any(), anyString(), anyString(), any()))
                .thenThrow(new IngestionProcessingException(FailureClass.PARSER_TIMEOUT, "parser timed out"));

        consumer.onMessage(message(2, 1, false), channel);

        verify(rabbitTemplate, org.mockito.Mockito.times(2))
                .convertAndSend(anyString(), anyString(), anyString(), any(MessagePostProcessor.class));
        verify(channel).basicAck(2, false);
    }

    @Test
    void twentiethRetryableFailureIsSentToDlq() throws Exception {
        when(idempotencyStore.process(any(), any(), anyString(), anyString(), any()))
                .thenThrow(new IngestionProcessingException(FailureClass.PARSER_TIMEOUT, "parser timed out"));

        consumer.onMessage(message(3, 20, false), channel);

        verify(rabbitTemplate, org.mockito.Mockito.times(2))
                .convertAndSend(anyString(), anyString(), anyString(), any(MessagePostProcessor.class));
        verify(channel).basicAck(3, false);
    }

    @Test
    void forbiddenPayloadIsNotRetried() throws Exception {
        consumer.onMessage(message(4, 1, true), channel);

        verify(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), anyString(), any(MessagePostProcessor.class));
        verify(channel).basicAck(4, false);
    }

    @Test
    void retryPublishFailureRequeuesWithoutAdvancingProcessing() throws Exception {
        when(idempotencyStore.process(any(), any(), anyString(), anyString(), any()))
                .thenThrow(new IngestionProcessingException(FailureClass.PARSER_TIMEOUT, "parser timed out"));
        org.mockito.Mockito.doThrow(new IllegalStateException("synthetic broker failure"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString(), any(MessagePostProcessor.class));

        consumer.onMessage(message(5, 1, false), channel);

        verify(channel).basicNack(5, false, true);
        org.mockito.Mockito.verify(channel, org.mockito.Mockito.never()).basicAck(5, false);
    }

    private Message message(long deliveryTag, int attempt, boolean forbidden) {
        UUID spaceId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = Map.of(
                "jobId", UUID.randomUUID(),
                "sourceId", UUID.randomUUID(),
                "documentRevisionId", UUID.randomUUID(),
                "pipelineVersionId", UUID.randomUUID(),
                "attemptId", UUID.randomUUID(),
                "operation", "DOCUMENT_UPSERT",
                "artifactRef", Map.of(
                        "artifactId", UUID.randomUUID(),
                        "mediaType", "text/markdown",
                        "byteLength", 128,
                        "sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "storageUri", "spaces/" + spaceId + "/sources/" + UUID.randomUUID()
                                + "/revisions/" + UUID.randomUUID() + "/artifacts/" + UUID.randomUUID()
                                + "/sha256/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        if (forbidden) {
            payload = new java.util.LinkedHashMap<>(payload);
            payload.put("fullText", "must never cross the message boundary");
        }
        Map<String, Object> envelope = Map.of(
                "eventId", eventId,
                "eventType", "ingestion.job.requested.v1",
                "occurredAt", Instant.now(),
                "producer", "test",
                "correlationId", UUID.randomUUID(),
                "causationId", UUID.randomUUID(),
                "spaceId", spaceId,
                "aggregateId", UUID.randomUUID(),
                "payload", payload);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        properties.setHeader("x-ragforge-delivery-attempt", attempt);
        return new Message(write(envelope), properties);
    }

    private byte[] write(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
