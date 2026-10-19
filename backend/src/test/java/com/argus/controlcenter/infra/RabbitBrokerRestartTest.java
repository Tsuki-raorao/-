package com.argus.controlcenter.infra;

import com.argus.controlcenter.domain.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.assertj.core.api.Assertions.*;

/** 两个进程间由验收脚本重启专用 Broker，验证真实持久化，不在测试中执行服务器命令。 */
class RabbitBrokerRestartTest {
    @Test @EnabledIfEnvironmentVariable(named="ARGUS_TEST_BROKER_RESTART",matches="prepare")
    void preparePersistentMessage() {
        var connection=MySqlMqRecoveryIntegrationTest.connection();
        try {
            var admin=new RabbitAdmin(connection);var exchange=new TopicExchange("argus-restart-check",true,false);
            var queue=new Queue("argus-restart-check",true);
            admin.declareExchange(exchange);admin.declareQueue(queue);
            admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("task.event"));
            var rabbit=new RabbitTemplate(connection);rabbit.setMandatory(true);
            new RabbitTaskEventPublisher(rabbit,new ObjectMapper(),exchange.getName(),"task.event")
                .publish("restart-check",1,null,TaskStatus.PENDING,"test","ACCEPTED",Instant.now());
            assertThat(admin.getQueueInfo(queue.getName()).getMessageCount()).isEqualTo(1);
        } finally { connection.destroy(); }
    }
    @Test @EnabledIfEnvironmentVariable(named="ARGUS_TEST_BROKER_RESTART",matches="verify")
    void verifyMessageAfterExternalBrokerRestart() {
        var connection=MySqlMqRecoveryIntegrationTest.connection();
        try {
            Message received=new RabbitTemplate(connection).receive("argus-restart-check",5000);
            assertThat(received).isNotNull();
            assertThat(received.getMessageProperties().getMessageId()).isEqualTo("restart-check:1");
            var admin=new RabbitAdmin(connection);admin.deleteQueue("argus-restart-check");admin.deleteExchange("argus-restart-check");
        } finally { connection.destroy(); }
    }
}
