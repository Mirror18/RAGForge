package com.ragforge.server.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Valkey Pub/Sub transport for post-commit run-event hints. Pub/Sub is deliberately
 * best-effort; PostgreSQL replay remains the durable recovery path.
 */
@Component
public class RunEventFanout implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(RunEventFanout.class);
    private static final String DEFAULT_CHANNEL = "ragforge:run-events:live";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RedisMessageListenerContainer listenerContainer;
    private final String channel;
    private final boolean enabled;
    private final CopyOnWriteArrayList<Consumer<RunEventFanoutEnvelope>> handlers = new CopyOnWriteArrayList<>();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong publishFailures = new AtomicLong();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong invalid = new AtomicLong();
    private final AtomicLong handlerFailures = new AtomicLong();
    private volatile boolean running;

    public RunEventFanout(StringRedisTemplate redis, ObjectMapper objectMapper,
                          RedisConnectionFactory connectionFactory,
                          @Value("${ragforge.run-events.fanout.channel:" + DEFAULT_CHANNEL + "}") String channel,
                          @Value("${ragforge.run-events.fanout.enabled:true}") boolean enabled) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.channel = channel == null || channel.isBlank() ? DEFAULT_CHANNEL : channel;
        this.enabled = enabled;
        this.listenerContainer = new RedisMessageListenerContainer();
        this.listenerContainer.setConnectionFactory(Objects.requireNonNull(connectionFactory, "connectionFactory"));
        this.listenerContainer.addMessageListener(new Listener(), new ChannelTopic(this.channel));
        this.listenerContainer.afterPropertiesSet();
    }

    /** Disabled transport for direct store tests that do not create a Spring context. */
    public static RunEventFanout disabled() {
        return new RunEventFanout();
    }

    private RunEventFanout() {
        this.redis = null;
        this.objectMapper = null;
        this.listenerContainer = null;
        this.channel = DEFAULT_CHANNEL;
        this.enabled = false;
    }

    public void register(Consumer<RunEventFanoutEnvelope> handler) {
        if (enabled) {
            handlers.add(Objects.requireNonNull(handler, "handler"));
        }
    }

    /** Registers a post-commit callback; a rolled-back transaction never emits a hint. */
    public void publishAfterCommit(RunEvent event) {
        if (!enabled) {
            return;
        }
        Objects.requireNonNull(event, "event");
        Runnable publish = () -> publishNow(event);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            // Direct store tests have no transaction synchronization. Production Spring
            // calls are transactional and therefore take the afterCommit branch above.
            publish.run();
        }
    }

    public FanoutMetrics metrics() {
        return new FanoutMetrics(published.get(), publishFailures.get(), received.get(), invalid.get(),
                handlerFailures.get(), handlers.size(), running);
    }

    private void publishNow(RunEvent event) {
        try {
            String body = objectMapper.writeValueAsString(RunEventFanoutEnvelope.from(event));
            redis.convertAndSend(channel, body);
            published.incrementAndGet();
        } catch (Exception exception) {
            publishFailures.incrementAndGet();
            log.warn("run event fan-out publish failed eventId={} runId={} spaceId={}",
                    event.eventId(), event.runId(), event.spaceId(), exception);
        }
    }

    private void receive(Message message) {
        received.incrementAndGet();
        try {
            RunEventFanoutEnvelope envelope = objectMapper.readValue(
                    message.getBody(), RunEventFanoutEnvelope.class);
            for (Consumer<RunEventFanoutEnvelope> handler : handlers) {
                try {
                    handler.accept(envelope);
                } catch (RuntimeException exception) {
                    handlerFailures.incrementAndGet();
                    log.warn("run event fan-out handler failed eventId={} runId={} spaceId={}",
                            envelope.eventId(), envelope.runId(), envelope.spaceId(), exception);
                }
            }
        } catch (Exception exception) {
            invalid.incrementAndGet();
            log.warn("run event fan-out message rejected", exception);
        }
    }

    @Override
    public void start() {
        if (enabled && !running) {
            listenerContainer.start();
            running = true;
        }
    }

    @Override
    public void stop() {
        if (running) {
            listenerContainer.stop();
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return enabled;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    public record FanoutMetrics(long published, long publishFailures, long received, long invalid,
                                long handlerFailures, int registeredHandlers, boolean listenerRunning) {
    }

    private final class Listener implements MessageListener {
        @Override
        public void onMessage(Message message, byte[] pattern) {
            receive(message);
        }
    }
}
