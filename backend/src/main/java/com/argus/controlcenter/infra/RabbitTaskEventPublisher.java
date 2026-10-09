package com.argus.controlcenter.infra;

import com.argus.controlcenter.domain.TaskStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** RabbitMQ 只做通知/异步消费，消费方必须回查 MySQL 获取权威状态。 */
@Component
@ConditionalOnProperty(prefix = "argus.messaging", name = "enabled", havingValue = "true")
public class RabbitTaskEventPublisher implements TaskEventPublisher {
    private final RabbitTemplate rabbit; private final ObjectMapper json; private final String exchange; private final String routingKey;
    public RabbitTaskEventPublisher(RabbitTemplate rabbit,ObjectMapper json,
                                    @Value("${argus.messaging.exchange:argus.events}") String exchange,
                                    @Value("${argus.messaging.task-events-routing-key:task.event}") String routingKey) {
        this.rabbit=rabbit; this.json=json; this.exchange=exchange; this.routingKey=routingKey;
    }
    @Override public void publish(String taskId,long sequence,TaskStatus from,TaskStatus to,String actor,String reason,Instant occurredAt) {
        Map<String,Object> event=new LinkedHashMap<>(); event.put("eventType","task.status.changed"); event.put("taskId",taskId); event.put("sequence",sequence);
        event.put("fromStatus",from==null?null:from.name()); event.put("toStatus",to.name()); event.put("actor",actor); event.put("reason",reason); event.put("occurredAt",occurredAt.toString());
        try { rabbit.convertAndSend(exchange,routingKey,json.writeValueAsString(event)); } catch (JsonProcessingException | RuntimeException ignored) { /* 事实可由 Outbox 重发 */ }
    }
}
