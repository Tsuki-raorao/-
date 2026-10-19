package com.argus.controlcenter.infra;

import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/** 仅接受显式的一次性本机测试库；用于 MySQL V9→V10 和恢复验收。 */
class MySqlMqRecoveryIntegrationTest {
    @Test void migratePreservingFactsThenRetryPublishAndConsume() throws Exception {
        String url=System.getenv("ARGUS_TEST_MQ_MYSQL_URL");
        assumeTrue(url!=null && !url.isBlank(),"isolated MySQL not configured");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/argus_test_stage1(?:\\?[^#]*)?");
        var source=new DriverManagerDataSource(url,System.getenv("ARGUS_TEST_MQ_MYSQL_USER"),System.getenv("ARGUS_TEST_MQ_MYSQL_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)).isZero();
        Flyway.configure().dataSource(source).target("9").load().migrate();
        Instant now=Instant.now();
        new NodeRepository(jdbc).save(new Node("mq-check-node","MQ check","127.0.0.1",NodeStatus.UNKNOWN,now));
        new InstanceRepository(jdbc).save(new Instance("mq-check-instance","check","mq-check-node","check","test",InstanceStatus.STOPPED,now));
        var task=new Task("mq-check-task","mq-check-instance","start",TaskStatus.PENDING,"ACCEPTED",now,null);
        task.setUpdatedAt(now); task.setNodeId("mq-check-node"); task.setAgentInstanceId("check");
        task.setCommandId("mq-check-command"); task.setResultCode("ACCEPTED");
        var tasks=new TaskRepository(jdbc);
        tasks.insert(new TaskCommand(task,"mq-check-key","http://127.0.0.1:8090","check-node","check-store",now.plusSeconds(300),1,false));
        tasks.appendEvent(task.getId(),1,null,TaskStatus.PENDING,"test","ACCEPTED",now);
        var before=jdbc.queryForList("SELECT * FROM tasks");
        Flyway.configure().dataSource(source).load().migrate();
        assertThat(jdbc.queryForList("SELECT * FROM tasks")).isEqualTo(before);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var outbox=new TaskEventOutboxRepository(jdbc);
        tx.executeWithoutResult(s->{outbox.append(task.getId(),1,null,TaskStatus.PENDING,"test","ACCEPTED",now);s.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_event_outbox",Integer.class)).isZero();
        tx.executeWithoutResult(s->outbox.append(task.getId(),1,null,TaskStatus.PENDING,"test","ACCEPTED",now));
        var abandoned=outbox.claim(1,now.plusSeconds(1),now.plusSeconds(2)).get(0);
        var recovered=outbox.claim(1,now.plusSeconds(3),now.plusSeconds(60)).get(0);
        outbox.markPublished(abandoned,now.plusSeconds(4));
        assertThat(jdbc.queryForObject("SELECT status FROM task_event_outbox",String.class)).isEqualTo("PROCESSING");
        outbox.markRetry(recovered,now.minusSeconds(1),"recovery-test");

        var connection=connection(); var admin=new RabbitAdmin(connection);
        String name="argus-mysql-check";
        try {
            var exchange=new TopicExchange(name,true,false); var queue=new Queue(name,true);
            admin.declareExchange(exchange); admin.declareQueue(queue);
            admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("task.event"));
            var rabbit=new RabbitTemplate(connection); rabbit.setMandatory(true);
            var publisher=new RabbitTaskEventPublisher(rabbit,new ObjectMapper(),name,"task.event");
            new TaskEventOutboxWorker(outbox,publisher).publishDue();
            assertThat(jdbc.queryForObject("SELECT status FROM task_event_outbox",String.class)).isEqualTo("PUBLISHED");
            Message message=rabbit.receive(name,5000); assertThat(message).isNotNull();
            var consumer=new TaskEventConsumer(jdbc,new ObjectMapper());
            tx.executeWithoutResult(s->{consumer.consume(message);s.setRollbackOnly();});
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_event_receipts",Integer.class)).isZero();
            tx.executeWithoutResult(s->consumer.consume(message));
            tx.executeWithoutResult(s->consumer.consume(message));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_event_receipts",Integer.class)).isEqualTo(1);
        } finally { admin.deleteQueue(name);admin.deleteExchange(name);connection.destroy(); }
    }

    static CachingConnectionFactory connection() {
        String port=System.getenv("ARGUS_TEST_RABBIT_PORT");
        assumeTrue(port!=null && !port.isBlank(),"isolated RabbitMQ not configured");
        var connection=new CachingConnectionFactory("127.0.0.1",Integer.parseInt(port));
        connection.setUsername(System.getenv("ARGUS_TEST_RABBIT_USER"));connection.setPassword(System.getenv("ARGUS_TEST_RABBIT_PASSWORD"));
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);connection.setPublisherReturns(true);
        return connection;
    }
}
