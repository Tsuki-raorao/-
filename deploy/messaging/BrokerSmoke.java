import com.rabbitmq.client.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Persistent-message restart smoke check; no business queue or task writes. */
public class BrokerSmoke {
    public static void main(String[] args) throws Exception {
        var env=new Properties();try(var reader=Files.newBufferedReader(Path.of(args[0]))){env.load(reader);}
        if(!"127.0.0.1".equals(env.getProperty("SPRING_RABBITMQ_HOST")) || !"25674".equals(env.getProperty("SPRING_RABBITMQ_PORT")) || !"argus-prod".equals(env.getProperty("SPRING_RABBITMQ_VIRTUAL_HOST")))throw new IllegalArgumentException("dedicated private production Broker required");
        var factory=new ConnectionFactory();factory.setHost("127.0.0.1");factory.setPort(25674);
        factory.setUsername(env.getProperty("SPRING_RABBITMQ_USERNAME"));factory.setPassword(env.getProperty("SPRING_RABBITMQ_PASSWORD"));factory.setVirtualHost("argus-prod");
        String queue="argus.production.readiness",id="argus-readiness-20261019";
        try(var connection=factory.newConnection("argus-production-readiness");var channel=connection.createChannel()){
            if(args[1].equals("prepare")){
                var declared=channel.queueDeclare(queue,true,false,false,null);
                if(declared.getMessageCount()!=0)throw new IllegalStateException("existing readiness messages preserved; inspect first");
                channel.confirmSelect();channel.basicPublish("",queue,true,new AMQP.BasicProperties.Builder().deliveryMode(2).messageId(id).build(),id.getBytes(StandardCharsets.UTF_8));channel.waitForConfirmsOrDie(5000);
                System.out.println("{\"persistentMessageConfirmed\":true,\"businessQueuesTouched\":false}");
            }else if(args[1].equals("verify")){
                var message=channel.basicGet(queue,false);
                if(message==null || !id.equals(message.getProps().getMessageId()) || !id.equals(new String(message.getBody(),StandardCharsets.UTF_8)) || !Integer.valueOf(2).equals(message.getProps().getDeliveryMode()))throw new IllegalStateException("readiness message not recovered");
                channel.basicAck(message.getEnvelope().getDeliveryTag(),false);channel.queueDelete(queue,false,true);
                System.out.println("{\"persistentMessageRecovered\":true,\"readinessQueueRemoved\":true,\"businessQueuesTouched\":false}");
            }else if(args[1].equals("queue-status")){
                var main=channel.queueDeclarePassive("argus.task-events.consumer.v1");var dead=channel.queueDeclarePassive("argus.task-events.dead");
                System.out.println("{\"ready\":"+main.getMessageCount()+",\"consumers\":"+main.getConsumerCount()+",\"dead\":"+dead.getMessageCount()+"}");
            }else if(args[1].equals("poison")){
                channel.confirmSelect();channel.basicPublish("argus.events","task.event",true,new AMQP.BasicProperties.Builder()
                    .messageId("argus-production-gray-poison-20261019").contentType("application/json").deliveryMode(2).build(),"{}".getBytes(StandardCharsets.UTF_8));
                channel.waitForConfirmsOrDie(5000);System.out.println("{\"controlledPoisonPublished\":true}");
            }else if(args[1].equals("remove-poison")){
                var message=channel.basicGet("argus.task-events.dead",false);
                if(message==null)throw new IllegalStateException("controlled poison missing");
                if(!"argus-production-gray-poison-20261019".equals(message.getProps().getMessageId())){
                    channel.basicNack(message.getEnvelope().getDeliveryTag(),false,true);throw new IllegalStateException("foreign dead letter preserved");
                }
                channel.basicAck(message.getEnvelope().getDeliveryTag(),false);System.out.println("{\"controlledPoisonRemoved\":true}");
            }else throw new IllegalArgumentException("unknown readiness phase");
        }
    }
}
