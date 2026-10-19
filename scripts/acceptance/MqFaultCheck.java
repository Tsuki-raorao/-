import com.rabbitmq.client.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Only the explicitly isolated acceptance Broker. Never purge a queue. */
public class MqFaultCheck {
    public static void main(String[] args) throws Exception {
        Properties env=new Properties();
        try(var reader=Files.newBufferedReader(Path.of(args[0]))){env.load(reader);}
        if(!"argus_test_acceptance".equals(env.getProperty("MYSQL_DATABASE"))
                || !"127.0.0.1".equals(env.getProperty("SPRING_RABBITMQ_HOST"))
                || !"25672".equals(env.getProperty("SPRING_RABBITMQ_PORT")))
            throw new IllegalArgumentException("isolated acceptance environment required");
        ConnectionFactory factory=new ConnectionFactory();factory.setHost("127.0.0.1");factory.setPort(25672);
        factory.setUsername(env.getProperty("SPRING_RABBITMQ_USERNAME"));factory.setPassword(env.getProperty("SPRING_RABBITMQ_PASSWORD"));
        try(var connection=factory.newConnection("argus-acceptance-fault");var channel=connection.createChannel()){
            switch(args[1]){
                case "poison" -> {
                    channel.confirmSelect();
                    channel.basicPublish("argus.events","task.event",true,new AMQP.BasicProperties.Builder()
                        .messageId("acceptance-poison-20261019").contentType("application/json").deliveryMode(2).build(),"{}".getBytes(StandardCharsets.UTF_8));
                    channel.waitForConfirmsOrDie(5000);System.out.println("{\"publishedPoison\":1}");
                }
                case "backlog" -> {
                    String task=args[2];if(!task.matches("[0-9a-f-]{36}"))throw new IllegalArgumentException("fixture task id required");
                    String id=task+":1";
                    String body="{\"eventId\":\""+id+"\",\"eventType\":\"task.status.changed\",\"taskId\":\""+task+"\",\"sequence\":1,\"toStatus\":\"PENDING\"}";
                    channel.confirmSelect();
                    for(int i=0;i<120;i++)channel.basicPublish("argus.events","task.event",true,new AMQP.BasicProperties.Builder()
                        .messageId(id).contentType("application/json").deliveryMode(2).build(),body.getBytes(StandardCharsets.UTF_8));
                    channel.waitForConfirmsOrDie(5000);System.out.println("{\"publishedDuplicates\":120}");
                }
                case "remove-own-poison" -> {
                    GetResponse response=channel.basicGet("argus.task-events.dead",false);
                    if(response==null)throw new IllegalStateException("expected poison missing");
                    if(!"acceptance-poison-20261019".equals(response.getProps().getMessageId())){
                        channel.basicNack(response.getEnvelope().getDeliveryTag(),false,true);
                        throw new IllegalStateException("foreign dead letter preserved; inspect manually");
                    }
                    channel.basicAck(response.getEnvelope().getDeliveryTag(),false);System.out.println("{\"removedOwnPoison\":1}");
                }
                case "status" -> System.out.println("{\"ready\":"+channel.queueDeclarePassive("argus.task-events.consumer.v1").getMessageCount()+",\"dead\":"+channel.queueDeclarePassive("argus.task-events.dead").getMessageCount()+"}");
                default -> throw new IllegalArgumentException("unknown action");
            }
        }
    }
}
