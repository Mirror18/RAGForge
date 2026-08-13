package com.ragforge.ingestion.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragforge.ingestion.common.UuidV7;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "ragforge.ingestion.enabled", havingValue = "true")
public class IngestionJobConsumer {
    private static final Logger log = LoggerFactory.getLogger(IngestionJobConsumer.class);
    private static final String STEP_NAME = "FETCH";

    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final RabbitMessagingProperties properties;
    private final JdbcIngestionIdempotencyStore idempotencyStore;
    private final IngestionSideEffectHandler sideEffectHandler;
    private final RetryPolicy retryPolicy;

    public IngestionJobConsumer(ObjectMapper objectMapper,
                                RabbitTemplate rabbitTemplate,
                                RabbitMessagingProperties properties,
                                JdbcIngestionIdempotencyStore idempotencyStore,
                                IngestionSideEffectHandler sideEffectHandler) {
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.idempotencyStore = idempotencyStore;
        this.sideEffectHandler = sideEffectHandler;
        this.retryPolicy = new RetryPolicy(properties.getMaxAttempts());
    }

    @RabbitListener(
            queues = "#{@rabbitMessagingProperties.requestedQueue}",
            ackMode = "MANUAL",
            autoStartup = "${ragforge.ingestion.enabled:false}")
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        int deliveryAttempt = deliveryAttempt(message);
        IngestionEventEnvelope envelope = null;
        try {
            envelope = objectMapper.readValue(message.getBody(), IngestionEventEnvelope.class);
            envelope.validate();
            IngestionJobRequestedPayload payload = objectMapper.treeToValue(
                    envelope.payload(), IngestionJobRequestedPayload.class);
            payload.validate();
            String idempotencyKey = envelope.eventId().toString();
            JdbcIngestionIdempotencyStore.ProcessResult result = idempotencyStore.process(
                    envelope, payload, STEP_NAME, idempotencyKey, sideEffectHandler);
            publishStatus(envelope, payload, result == JdbcIngestionIdempotencyStore.ProcessResult.DUPLICATE
                    ? "COMPLETED" : "COMPLETED", deliveryAttempt, null, null);
            channel.basicAck(deliveryTag, false);
            log.info("Ingestion message acknowledged eventId={} spaceId={} jobId={} attempt={} result={}",
                    envelope.eventId(), envelope.spaceId(), payload.jobId(), deliveryAttempt, result);
        } catch (Exception exception) {
            Failure failure = classify(exception);
            RetryPolicy.Decision decision = retryPolicy.decide(deliveryAttempt, failure.failureClass());
            if (decision.action() == RetryPolicy.Action.RETRY && envelope != null) {
                try {
                    publishRetry(message, decision);
                    publishStatus(envelope, requestedPayloadOrNull(envelope), "RETRY_SCHEDULED",
                            deliveryAttempt, failure, decision.delay());
                    channel.basicAck(deliveryTag, false);
                    log.warn("Ingestion message scheduled for retry eventId={} spaceId={} attempt={} failureClass={}",
                            envelope.eventId(), envelope.spaceId(), deliveryAttempt, failure.failureClass());
                    return;
                } catch (RuntimeException retryPublishFailure) {
                    channel.basicNack(deliveryTag, false, true);
                    log.error("Retry publish failed; message requeued eventId={} deliveryAttempt={}",
                            envelope.eventId(), deliveryAttempt, retryPublishFailure);
                    return;
                }
            }
            try {
                publishDeadLetter(message, envelope, failure, deliveryAttempt);
                if (envelope != null) {
                    publishStatus(envelope, requestedPayloadOrNull(envelope), "DLQ",
                            deliveryAttempt, failure, null);
                }
                channel.basicAck(deliveryTag, false);
                log.error("Ingestion message sent to DLQ eventId={} spaceId={} deliveryAttempt={} failureClass={}",
                        envelope == null ? null : envelope.eventId(),
                        envelope == null ? null : envelope.spaceId(), deliveryAttempt, failure.failureClass());
            } catch (RuntimeException deadLetterFailure) {
                channel.basicNack(deliveryTag, false, true);
                log.error("DLQ publish failed; message requeued eventId={} deliveryAttempt={}",
                        envelope == null ? null : envelope.eventId(), deliveryAttempt, deadLetterFailure);
            }
        }
    }

    private void publishRetry(Message message, RetryPolicy.Decision decision) {
        rabbitTemplate.convertAndSend(properties.getExchange(), properties.getRetryRoutingKey(),
                new String(message.getBody(), StandardCharsets.UTF_8), outgoing -> {
                    outgoing.getMessageProperties().setContentType("application/json");
                    outgoing.getMessageProperties().setHeader("x-ragforge-delivery-attempt", decision.attempt() + 1);
                    outgoing.getMessageProperties().setExpiration(Long.toString(decision.delay().toMillis()));
                    return outgoing;
                });
    }

    private void publishDeadLetter(Message message, IngestionEventEnvelope envelope,
                                   Failure failure, int deliveryAttempt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", envelope == null ? null : envelope.eventId());
        body.put("eventType", envelope == null ? "UNKNOWN" : envelope.eventType());
        body.put("spaceId", envelope == null ? null : envelope.spaceId());
        body.put("aggregateId", envelope == null ? null : envelope.aggregateId());
        body.put("failureClass", failure.failureClass().name());
        body.put("message", failure.safeMessage());
        body.put("deliveryAttempt", deliveryAttempt);
        try {
            String serialized = objectMapper.writeValueAsString(body);
            rabbitTemplate.convertAndSend(properties.getExchange(), properties.getDeadLetterRoutingKey(), serialized,
                    outgoing -> {
                        outgoing.getMessageProperties().setContentType("application/json");
                        return outgoing;
                    });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("DLQ envelope serialization failed", exception);
        }
    }

    private void publishStatus(IngestionEventEnvelope envelope, IngestionJobRequestedPayload payload,
                               String status, int deliveryAttempt, Failure failure, java.time.Duration delay) {
        if (envelope == null || payload == null) {
            return;
        }
        ObjectNode statusPayload = objectMapper.createObjectNode();
        statusPayload.put("jobId", payload.jobId().toString());
        statusPayload.put("sourceId", payload.sourceId().toString());
        statusPayload.put("documentRevisionId", payload.documentRevisionId().toString());
        statusPayload.put("pipelineVersionId", payload.pipelineVersionId().toString());
        statusPayload.put("attemptId", payload.attemptId().toString());
        statusPayload.put("stepName", STEP_NAME);
        statusPayload.put("status", status);
        statusPayload.put("idempotencyKey", envelope.eventId().toString());
        statusPayload.put("deliveryAttempt", deliveryAttempt);
        if (failure == null) {
            statusPayload.putNull("failure");
        } else {
            ObjectNode failureNode = statusPayload.putObject("failure");
            failureNode.put("code", failure.failureClass().name());
            failureNode.put("retryable", failure.failureClass().retryable());
            failureNode.put("message", failure.safeMessage());
            if (delay != null && !delay.isZero()) {
                failureNode.put("nextRetryAt", Instant.now().plus(delay).toString());
            } else {
                failureNode.putNull("nextRetryAt");
            }
        }
        if (delay == null || delay.isZero()) {
            statusPayload.putNull("retry");
        } else {
            ObjectNode retry = statusPayload.putObject("retry");
            retry.put("attempt", deliveryAttempt);
            retry.put("maxAttempts", retryPolicy.maxAttempts());
            retry.put("backoffSeconds", Math.max(1, delay.toSeconds()));
        }
        ObjectNode sideEffects = statusPayload.putObject("sideEffects");
        sideEffects.put("revisionPersisted", false);
        sideEffects.put("artifactPersisted", false);
        sideEffects.put("parseReportPersisted", false);
        sideEffects.put("activePointerUpdated", false);
        sideEffects.put("checkpointAdvanced", false);

        IngestionEventEnvelope statusEnvelope = new IngestionEventEnvelope(
                UuidV7.random(), "ingestion.job.status.changed.v1", Instant.now(),
                "ragforge-ingestion-worker", envelope.correlationId(), envelope.eventId(),
                envelope.spaceId(), envelope.aggregateId(), statusPayload);
        try {
            rabbitTemplate.convertAndSend(properties.getExchange(), properties.getStatusRoutingKey(),
                    objectMapper.writeValueAsString(statusEnvelope), outgoing -> {
                        outgoing.getMessageProperties().setContentType("application/json");
                        return outgoing;
                    });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Status envelope serialization failed", exception);
        }
    }

    private IngestionJobRequestedPayload requestedPayloadOrNull(IngestionEventEnvelope envelope) {
        try {
            return objectMapper.treeToValue(envelope.payload(), IngestionJobRequestedPayload.class);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private int deliveryAttempt(Message message) {
        Object header = message.getMessageProperties().getHeaders().get("x-ragforge-delivery-attempt");
        if (header instanceof Number number) {
            return Math.max(1, Math.min(properties.getMaxAttempts(), number.intValue()));
        }
        try {
            return header == null ? 1 : Math.max(1, Math.min(properties.getMaxAttempts(), Integer.parseInt(header.toString())));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private Failure classify(Exception exception) {
        if (exception instanceof EnvelopeValidationException) {
            return new Failure(FailureClass.SECURITY_POLICY_REJECTED, safeMessage(exception));
        }
        if (exception instanceof IngestionProcessingException processingException) {
            return new Failure(processingException.failureClass(), safeMessage(processingException));
        }
        return new Failure(FailureClass.DATABASE_UNAVAILABLE, safeMessage(exception));
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        String redacted = message.replaceAll("(?i)(password|secret|token|api[_-]?key|credential)[^,; ]*", "$1=[REDACTED]");
        return redacted.substring(0, Math.min(500, redacted.length()));
    }

    private record Failure(FailureClass failureClass, String safeMessage) { }
}
