package com.argus.controlcenter.infra;

import com.argus.controlcenter.domain.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import db.migration.V10__task_event_receipts;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
import static org.mockito.Mockito.*;

class TaskEventConsumerIntegrationTest {
    @Configuration @EnableRabbit @EnableTransactionManagement
    static class Infrastructure { }

    @Test void transactionalConsumerDeduplicatesAndDeadLettersFailures() throws Exception {
        String port=System.getenv("ARGUS_TEST_RABBIT_PORT");
        assumeTrue(port!=null && !port.isBlank(),"isolated RabbitMQ not configured");
        var fixture=new TaskEventOutboxTest(); fixture.prepare();
        fixture.jdbc.execute("ALTER TABLE tasks ADD COLUMN status VARCHAR(16) DEFAULT 'SUCCEEDED'");
        fixture.jdbc.execute("CREATE TABLE task_events(task_id VARCHAR(64),event_sequence BIGINT,to_status VARCHAR(16))");
        fixture.jdbc.update("INSERT INTO task_events VALUES('task-1',1,'PENDING'),('task-1',2,'SUCCEEDED'),('task-1',3,'RUNNING')");
        try(var c=fixture.jdbc.getDataSource().getConnection()) {
            var context=mock(Context.class); when(context.getConnection()).thenReturn(c);
            new V10__task_event_receipts().migrate(context);
        }
        var connection=new CachingConnectionFactory("127.0.0.1",Integer.parseInt(port));
        connection.setUsername(System.getenv("ARGUS_TEST_RABBIT_USER")); connection.setPassword(System.getenv("ARGUS_TEST_RABBIT_PASSWORD"));
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED); connection.setPublisherReturns(true);
        String name="argus-consume-check-"+UUID.randomUUID();
        var app=new AnnotationConfigApplicationContext();
        app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("check",Map.of(
                "argus.messaging.enabled","true","argus.messaging.exchange",name,"argus.messaging.consumer-queue",name)));
        app.registerBean("connectionFactory",CachingConnectionFactory.class,()->connection);
        app.registerBean("jdbcTemplate",org.springframework.jdbc.core.JdbcTemplate.class,()->fixture.jdbc);
        app.registerBean("transactionManager",PlatformTransactionManager.class,()->new DataSourceTransactionManager(fixture.jdbc.getDataSource()));
        app.registerBean("objectMapper",ObjectMapper.class,()->new ObjectMapper());
        app.registerBean(RabbitAdmin.class,()->new RabbitAdmin(connection));
        app.register(Infrastructure.class,MessagingTopology.class,TaskEventConsumer.class);
        var admin=new RabbitAdmin(connection);
        try {
            app.refresh();
            var template=new RabbitTemplate(connection); template.setMandatory(true);
            var publisher=new RabbitTaskEventPublisher(template,new ObjectMapper(),name,"task.event");
            publisher.publish("task-1",2,null,TaskStatus.SUCCEEDED,"test","DONE",Instant.now());
            publisher.publish("task-1",1,null,TaskStatus.PENDING,"test","ACCEPTED",Instant.now());
            publisher.publish("task-1",2,null,TaskStatus.SUCCEEDED,"test","DONE",Instant.now());
            long deadline=System.nanoTime()+10_000_000_000L;
            while(System.nanoTime()<deadline && fixture.jdbc.queryForObject("SELECT COUNT(*) FROM task_event_receipts",Integer.class)<2) Thread.sleep(50);
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM task_event_receipts",Integer.class)).isEqualTo(2);
            assertThat(fixture.jdbc.queryForList("SELECT observed_status FROM task_event_receipts",String.class)).containsOnly("SUCCEEDED");
            assertThat(fixture.jdbc.queryForObject("SELECT status FROM tasks WHERE id='task-1'",String.class)).isEqualTo("SUCCEEDED");

            // 无法解析的消息有界重试后进死信，不无限重回原队列。
            template.convertAndSend(name,"task.event","invalid-json");
            Message invalid=template.receive("argus.task-events.dead",10000);
            assertThat(invalid).isNotNull();

            // 注入数据库写失败，收件事务失败不能 ACK 成功，最终进入死信。
            fixture.jdbc.execute("ALTER TABLE task_event_receipts ADD CONSTRAINT reject_sequence_three CHECK(event_sequence<3)");
            publisher.publish("task-1",3,null,TaskStatus.RUNNING,"test","RUNNING",Instant.now());
            Message failed=template.receive("argus.task-events.dead",10000);
            assertThat(failed).isNotNull();
            assertThat(failed.getMessageProperties().getMessageId()).isEqualTo("task-1:3");
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM task_event_receipts",Integer.class)).isEqualTo(2);
        } finally {
            try {
                if(app.isActive()) app.getBean(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class).stop();
                admin.deleteQueue(name); admin.deleteExchange(name);
                admin.deleteQueue("argus.task-events.dead"); admin.deleteExchange("argus.task-events.dead");
            } finally { app.close(); connection.destroy(); }
        }
    }
}
