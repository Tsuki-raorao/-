package com.argus.controlcenter.infra;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 任务事件拓扑；启用 MQ 时由 RabbitAdmin 声明，停用时不连接消息服务。 */
@Configuration
@ConditionalOnProperty(prefix = "argus.messaging", name = "enabled", havingValue = "true")
public class MessagingTopology {
    @Bean TopicExchange argusEventsExchange(@Value("${argus.messaging.exchange:argus.events}") String name) { return new TopicExchange(name, true, false); }
    @Bean Queue argusTaskEventsQueue(@Value("${argus.messaging.consumer-queue:argus.task-events.consumer.v1}") String name) {
        return QueueBuilder.durable(name).deadLetterExchange("argus.task-events.dead").deadLetterRoutingKey("failed").build();
    }
    @Bean DirectExchange taskEventsDeadExchange() { return new DirectExchange("argus.task-events.dead",true,false); }
    @Bean Queue taskEventsDeadQueue() { return new Queue("argus.task-events.dead",true); }
    @Bean Binding taskEventsDeadBinding() {
        return BindingBuilder.bind(taskEventsDeadQueue()).to(taskEventsDeadExchange()).with("failed");
    }
    @Bean SimpleRabbitListenerContainerFactory taskEventListenerFactory(ConnectionFactory connectionFactory,
            @Value("${spring.rabbitmq.listener.simple.auto-startup:true}") boolean autoStartup) {
        var factory=new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        // This custom factory must explicitly honor Boot's operational pause setting.
        factory.setAutoStartup(autoStartup);
        factory.setConcurrentConsumers(1); factory.setMaxConcurrentConsumers(2); factory.setPrefetchCount(10);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(3).backOffOptions(250,2,1000)
                .recoverer(new RejectAndDontRequeueRecoverer()).build());
        return factory;
    }
    @Bean Binding argusTaskEventsBinding(Queue argusTaskEventsQueue, TopicExchange argusEventsExchange,
                                         @Value("${argus.messaging.task-events-routing-key:task.event}") String key) {
        return BindingBuilder.bind(argusTaskEventsQueue).to(argusEventsExchange).with(key);
    }
}
