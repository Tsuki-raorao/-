package com.argus.controlcenter.service;

import com.argus.controlcenter.config.AgentGatewayProperties;
import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.repository.NodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentCollectionConcurrencyTest {
    final NodeRepository nodes=mock(NodeRepository.class);
    final AgentGatewayService gateway=mock(AgentGatewayService.class);
    final AgentSnapshotStore store=mock(AgentSnapshotStore.class);
    final ObjectMapper json=new ObjectMapper();

    AgentSnapshotSyncService service(int count) throws Exception {
        var p=new AgentGatewayProperties();p.setEnabled(true);p.setSyncConcurrency(2);
        List<Node> values=new ArrayList<>();
        for(int i=0;i<count;i++){
            var node=new Node("node-"+i,"Node "+i,"http://127.0.0.1",NodeStatus.UNKNOWN,null);
            values.add(node);when(nodes.findById(node.getId())).thenReturn(Optional.of(node));
        }
        when(nodes.findAll()).thenReturn(values);when(nodes.updateExisting(any())).thenReturn(true);
        when(gateway.instances(any())).thenReturn(json.readTree("[]"));
        return new AgentSnapshotSyncService(nodes,gateway,p,store);
    }

    @Test void boundedWorkersAndOverlappingRoundDoesNotDuplicateCollection() throws Exception {
        var collector=service(8);var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        var active=new AtomicInteger();var peak=new AtomicInteger();
        when(gateway.health(any())).thenAnswer(call->{
            peak.accumulateAndGet(active.incrementAndGet(),Math::max);entered.countDown();
            try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("release missing");return json.readTree("{\"status\":\"UP\"}");}
            finally{active.decrementAndGet();}
        });
        var caller=Executors.newSingleThreadExecutor();
        try{
            var round=caller.submit(collector::syncAll);assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            collector.syncAll(); // Existing round owns the gate; no second batch is queued.
            verify(gateway,times(2)).health(any());release.countDown();round.get(5,TimeUnit.SECONDS);
            assertThat(peak.get()).isEqualTo(2);verify(store,times(8)).save(any(),any(),any());
        }finally{release.countDown();caller.shutdownNow();collector.close();}
    }

    @Test void sameNodeCannotHaveTwoConcurrentHttpCollections() throws Exception {
        var collector=service(1);var first=new CountDownLatch(1);var release=new CountDownLatch(1);
        var active=new AtomicInteger();var peak=new AtomicInteger();
        when(gateway.health(any())).thenAnswer(call->{
            peak.accumulateAndGet(active.incrementAndGet(),Math::max);first.countDown();
            try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("release missing");return json.readTree("{\"status\":\"UP\"}");}
            finally{active.decrementAndGet();}
        });
        var caller=Executors.newFixedThreadPool(2);
        try{
            var one=caller.submit(()->collector.syncNode("node-0"));assertThat(first.await(3,TimeUnit.SECONDS)).isTrue();
            var secondStarted=new CountDownLatch(1);
            var two=caller.submit(()->{secondStarted.countDown();collector.syncNode("node-0");});
            assertThat(secondStarted.await(3,TimeUnit.SECONDS)).isTrue();
            verify(gateway,after(150).times(1)).health(any());release.countDown();
            one.get(5,TimeUnit.SECONDS);two.get(5,TimeUnit.SECONDS);
            assertThat(peak.get()).isEqualTo(1);verify(store,times(2)).save(any(),any(),any());
        }finally{release.countDown();caller.shutdownNow();collector.close();}
    }

    @Test void unexpectedFailureOfOneNodeDoesNotAbortHealthyNodes() throws Exception {
        var collector=service(5);when(nodes.findById("node-0")).thenThrow(new IllegalStateException("fixture"));
        when(gateway.health(any())).thenReturn(json.readTree("{\"status\":\"UP\"}"));
        try{collector.syncAll();verify(store,times(4)).save(any(),any(),any());}
        finally{collector.close();}
    }
}
