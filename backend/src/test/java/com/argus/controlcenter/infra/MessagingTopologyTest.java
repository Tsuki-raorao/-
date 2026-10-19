package com.argus.controlcenter.infra;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MessagingTopologyTest {
    @Test void listenerPauseDoesNotGetLostInCustomFactory() {
        var endpoint=new SimpleRabbitListenerEndpoint();
        endpoint.setId("paused-acceptance-listener");endpoint.setQueueNames("acceptance-only");
        endpoint.setMessageListener(message -> { throw new AssertionError("paused listener must not receive"); });
        var factory=new MessagingTopology().taskEventListenerFactory(mock(ConnectionFactory.class),false);
        var container=factory.createListenerContainer(endpoint);
        assertThat(container.isAutoStartup()).isFalse();
        assertThat(container.isRunning()).isFalse();
    }
}
