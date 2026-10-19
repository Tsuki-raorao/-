package com.argus.controlcenter.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将经核实的任务事件持久化为通知收件记录；不根据消息重放任务或改写状态。 */
@Service
@ConditionalOnProperty(prefix="argus.messaging",name="enabled",havingValue="true")
public class TaskEventConsumer {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public TaskEventConsumer(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    @RabbitListener(queues="${argus.messaging.consumer-queue:argus.task-events.consumer.v1}",containerFactory="taskEventListenerFactory")
    @Transactional
    public void consume(Message message) {
        String id, task, status; long sequence;
        try {
            if(message.getBody().length>65536) throw new IllegalArgumentException();
            var value=json.readTree(message.getBody());
            if(!value.path("taskId").isTextual() || !value.path("sequence").isIntegralNumber()
                    || !value.path("sequence").canConvertToLong()) throw new IllegalArgumentException();
            task=value.path("taskId").asText(); sequence=value.path("sequence").asLong();
            id=value.path("eventId").asText(); status=value.path("toStatus").asText();
            if(task.isBlank() || task.length()>64 || sequence<1 || !id.equals(task+":"+sequence)
                    || !id.equals(message.getMessageProperties().getMessageId())
                    || !"task.status.changed".equals(value.path("eventType").asText())) throw new IllegalArgumentException();
        } catch(Exception invalid) { throw new AmqpRejectAndDontRequeueException("INVALID_TASK_EVENT"); }
        // 去重及业务收件落在同一事务；ACK 由容器在事务提交、方法成功返回后发送。
        if(jdbc.queryForObject("SELECT COUNT(*) FROM task_event_receipts WHERE event_id=?",Integer.class,id)>0) return;
        var facts=jdbc.query("SELECT e.to_status,t.status FROM task_events e JOIN tasks t ON t.id=e.task_id WHERE e.task_id=? AND e.event_sequence=?",
                (rs,row)->new String[]{rs.getString(1),rs.getString(2)},task,sequence);
        if(facts.size()!=1 || !status.equals(facts.get(0)[0])) throw new AmqpRejectAndDontRequeueException("TASK_EVENT_FACT_MISMATCH");
        try {
            jdbc.update("INSERT INTO task_event_receipts(event_id,task_id,event_sequence,event_status,observed_status,consumed_at) VALUES(?,?,?,?,?,?)",
                    id,task,sequence,status,facts.get(0)[1],Timestamp.from(Instant.now()));
        } catch(DuplicateKeyException duplicate) {
            // 多消费者并发中，已提交的唯一事件获胜；没有第二次外部副作用。
            if(jdbc.queryForObject("SELECT COUNT(*) FROM task_event_receipts WHERE event_id=?",Integer.class,id)!=1) throw duplicate;
        }
    }
}
