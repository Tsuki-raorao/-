package com.argus.controlcenter.infra;

import com.argus.controlcenter.domain.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/** 只连接显式提供的本机测试 Broker（可使用 SSH 隧道），不复用生产凭据。 */
class RabbitPublisherIntegrationTest {
    @Test void brokerConfirmsPersistentEventAndRejectsUnroutableEvent() throws Exception {
        String port = System.getenv("ARGUS_TEST_RABBIT_PORT");
        assumeTrue(port != null && !port.isBlank(), "isolated RabbitMQ not configured");
        var connection = new CachingConnectionFactory("127.0.0.1",Integer.parseInt(port));
        connection.setUsername(System.getenv("ARGUS_TEST_RABBIT_USER"));
        connection.setPassword(System.getenv("ARGUS_TEST_RABBIT_PASSWORD"));
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        String name="argus-check-"+UUID.randomUUID();
        var admin=new RabbitAdmin(connection);
        try {
            var exchange=new TopicExchange(name,true,false);
            var queue=new Queue(name,true);
            admin.declareExchange(exchange); admin.declareQueue(queue);
            admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("task.event"));
            var template=new RabbitTemplate(connection); template.setMandatory(true);
            var json=new ObjectMapper();
            var publisher=new RabbitTaskEventPublisher(template,json,name,"task.event");
            publisher.publish("task-1",1,null,TaskStatus.PENDING,"test","ACCEPTED",Instant.now());
            Message received=template.receive(name,5000);
            assertThat(received).isNotNull();
            assertThat(received.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(received.getMessageProperties().getMessageId()).isEqualTo("task-1:1");
            assertThat(json.readTree(received.getBody()).path("eventId").asText()).isEqualTo("task-1:1");
            var unroutable=new RabbitTaskEventPublisher(template,json,name,"unbound.key");
            assertThatThrownBy(() -> unroutable.publish("task-1",2,null,TaskStatus.RUNNING,"test","RUNNING",Instant.now()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unroutable");
            // 真正走数据库 Outbox -> Worker -> Broker，再读回队列，而非只测直接 Publisher。
            var fixture = new TaskEventOutboxTest();
            fixture.prepare(); fixture.append();
            new TaskEventOutboxWorker(fixture.outbox,publisher).publishDue();
            assertThat(fixture.status()).isEqualTo("PUBLISHED");
            Message throughOutbox=template.receive(name,5000);
            assertThat(throughOutbox).isNotNull();
            assertThat(throughOutbox.getMessageProperties().getMessageId()).isEqualTo("task-1:1");
            // 重试会保留同一稳定事件标识，消费者可用该标识去重。
            assertThat(throughOutbox.getMessageProperties().getMessageId()).isEqualTo(received.getMessageProperties().getMessageId());
        } finally {
            admin.deleteQueue(name); admin.deleteExchange(name); connection.destroy();
        }
    }
}
