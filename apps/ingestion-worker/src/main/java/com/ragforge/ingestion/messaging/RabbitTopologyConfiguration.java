package com.ragforge.ingestion.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RabbitMessagingProperties.class)
public class RabbitTopologyConfiguration {
    @Bean
    public DirectExchange ingestionExchange(RabbitMessagingProperties properties) {
        return new DirectExchange(properties.getExchange(), true, false);
    }

    @Bean
    public Queue requestedQueue(RabbitMessagingProperties properties) {
        return QueueBuilder.durable(properties.getRequestedQueue()).build();
    }

    @Bean
    public Queue retryQueue(RabbitMessagingProperties properties) {
        return QueueBuilder.durable(properties.getRetryQueue())
                .withArgument("x-dead-letter-exchange", properties.getExchange())
                .withArgument("x-dead-letter-routing-key", properties.getRequestedRoutingKey())
                .build();
    }

    @Bean
    public Queue deadLetterQueue(RabbitMessagingProperties properties) {
        return QueueBuilder.durable(properties.getDeadLetterQueue()).build();
    }

    @Bean
    public Queue statusQueue(RabbitMessagingProperties properties) {
        return QueueBuilder.durable(properties.getStatusQueue()).build();
    }

    @Bean
    public Declarables ingestionBindings(
            DirectExchange ingestionExchange,
            Queue requestedQueue,
            Queue retryQueue,
            Queue deadLetterQueue,
            Queue statusQueue,
            RabbitMessagingProperties properties) {
        Binding requested = BindingBuilder.bind(requestedQueue).to(ingestionExchange)
                .with(properties.getRequestedRoutingKey());
        Binding retry = BindingBuilder.bind(retryQueue).to(ingestionExchange)
                .with(properties.getRetryRoutingKey());
        Binding deadLetter = BindingBuilder.bind(deadLetterQueue).to(ingestionExchange)
                .with(properties.getDeadLetterRoutingKey());
        Binding status = BindingBuilder.bind(statusQueue).to(ingestionExchange)
                .with(properties.getStatusRoutingKey());
        return new Declarables(requested, retry, deadLetter, status);
    }
}
