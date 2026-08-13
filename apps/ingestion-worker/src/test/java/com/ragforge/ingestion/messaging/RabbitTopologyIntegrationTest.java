package com.ragforge.ingestion.messaging;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RabbitTopologyIntegrationTest {
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Test
    void durableRequestedAndDeadLetterRoutesAreIsolated() {
        RabbitMessagingProperties properties = new RabbitMessagingProperties();
        String suffix = "-" + System.nanoTime();
        properties.setExchange(properties.getExchange() + suffix);
        properties.setRequestedQueue(properties.getRequestedQueue() + suffix);
        properties.setDeadLetterQueue(properties.getDeadLetterQueue() + suffix);
        properties.setRequestedRoutingKey(properties.getRequestedRoutingKey() + suffix);
        properties.setDeadLetterRoutingKey(properties.getDeadLetterRoutingKey() + suffix);

        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connectionFactory.setUsername("guest");
        connectionFactory.setPassword("guest");
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        DirectExchange exchange = new DirectExchange(properties.getExchange(), true, false);
        org.springframework.amqp.core.Queue requested = QueueBuilder.durable(properties.getRequestedQueue()).build();
        org.springframework.amqp.core.Queue dlq = QueueBuilder.durable(properties.getDeadLetterQueue()).build();
        admin.declareExchange(exchange);
        admin.declareQueue(requested);
        admin.declareQueue(dlq);
        admin.declareBinding(BindingBuilder.bind(requested).to(exchange).with(properties.getRequestedRoutingKey()));
        admin.declareBinding(BindingBuilder.bind(dlq).to(exchange).with(properties.getDeadLetterRoutingKey()));

        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.convertAndSend(properties.getExchange(), properties.getRequestedRoutingKey(), "synthetic-request");
        org.springframework.amqp.core.Message received = template.receive(properties.getRequestedQueue(), 5_000);

        assertThat(received).isNotNull();
        assertThat(new String(received.getBody())).isEqualTo("synthetic-request");
        assertThat(admin.getQueueInfo(properties.getDeadLetterQueue())).isNotNull();
        connectionFactory.destroy();
    }
}
