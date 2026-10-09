package com.argus.controlcenter.infra;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 任务事件拓扑；启用 MQ 时由 RabbitAdmin 声明，停用时不连接消息服务。 */
@Configuration
@ConditionalOnProperty(prefix = "argus.messaging", name = "enabled", havingValue = "true")
public class MessagingTopology {
    @Bean TopicExchange argusEventsExchange(@Value("${argus.messaging.exchange:argus.events}") String name) { return new TopicExchange(name, true, false); }
    @Bean Queue argusTaskEventsQueue() { return new Queue("argus.task-events", true); }
    @Bean Binding argusTaskEventsBinding(Queue argusTaskEventsQueue, TopicExchange argusEventsExchange,
                                         @Value("${argus.messaging.task-events-routing-key:task.event}") String key) {
        return BindingBuilder.bind(argusTaskEventsQueue).to(argusEventsExchange).with(key);
    }
}
