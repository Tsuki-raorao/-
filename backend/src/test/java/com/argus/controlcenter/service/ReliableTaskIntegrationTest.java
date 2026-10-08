package com.argus.controlcenter.service;

import com.argus.controlcenter.config.*;
import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.exception.TaskControlException;
import com.argus.controlcenter.repository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:task-contract;DB_CLOSE_DELAY=-1",
        "argus.demo-data.enabled=false","argus.security.api-auth-required=true",
        "argus.security.api-access-token=test-view","argus.security.api-control-token=test-operator",
        "argus.security.read-only=false","argus.agent.enabled=true","argus.agent.allow-control=true",
        "argus.agent.require-allowlist=true","argus.agent.allowed-hosts=127.0.0.1","argus.agent.auth-token=test-read",
        "argus.agent.connect-timeout=1s","argus.agent.read-timeout=1s","argus.agent.sync-initial-delay-ms=86400000",
        "argus.tasks.control-enabled=true","argus.tasks.agent-control-token=test-control","argus.tasks.lease=10s",
        "argus.tasks.allowed-instance-ids=instance-a,instance-b","argus.tasks.initial-delay-ms=86400000"
})
@ActiveProfiles("test")
class ReliableTaskIntegrationTest {
    @Autowired TaskService tasks;
    @Autowired TaskWorker worker;
    @Autowired TaskQueueStore store;
    @Autowired TaskRepository repository;
    @Autowired NodeRepository nodes;
    @Autowired InstanceRepository instances;
    @Autowired NodeService nodeService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired TaskControlProperties properties;
    @Autowired SecurityProperties security;
    @Autowired AgentGatewayProperties gatewayProperties;
    @LocalServerPort int port;
    FakeAgent a,b;

    @BeforeEach void prepare() throws Exception {
        for(String table:List.of("task_events","instance_task_locks","task_queue","tasks","logs","instances","nodes")) jdbc.update("DELETE FROM "+table);
        properties.setControlEnabled(true);properties.setAllowDocker(false);properties.setAllowedInstanceIds(Set.of("instance-a","instance-b"));
        properties.setCommandTtl(Duration.ofMinutes(5));properties.setConfirmationGrace(Duration.ofMinutes(5));
        security.setReadOnly(false);gatewayProperties.setAllowControl(true);
        a=new FakeAgent("reported-node-a");b=new FakeAgent("reported-node-b");
        seed("node-a","instance-a",a);seed("node-b","instance-b",b);
    }
    @AfterEach void close() {
        if(a!=null)a.close();if(b!=null)b.close();
    }
    void seed(String nodeId,String instanceId,FakeAgent agent) {
        nodes.save(new Node(nodeId,nodeId,agent.address(),NodeStatus.ONLINE,Instant.now()));
        Instance instance=new Instance(instanceId,"same name",nodeId,"mc01","test",InstanceStatus.STOPPED,Instant.parse("2026-01-01T00:00:00Z"));
        instance.setAgentInstanceId("mc01");instance.setDataSource("MOCK");
        instances.save(instance);
    }
    Task create(String id) { return tasks.execute(id,"RESTART","MOCK",UUID.randomUUID().toString(),true); }
    void due() { jdbc.update("UPDATE task_queue SET next_run_at=?",Timestamp.from(Instant.EPOCH)); }
    Task run(Task task) {
        jdbc.update("UPDATE task_queue SET next_run_at=?",Timestamp.from(Instant.now().plusSeconds(86400)));
        jdbc.update("UPDATE task_queue SET next_run_at=? WHERE task_id=?",Timestamp.from(Instant.EPOCH),task.getId());
        worker.runOnce();return tasks.findById(task.getId());
    }

    @Test void twoNodesSameNameAreIsolatedAndSuccessNeverChangesObservations() {
        Task first=create("instance-a"),second=create("instance-b");
        worker.runOnce();worker.runOnce();
        assertThat(tasks.findById(first.getId()).getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(tasks.findById(second.getId()).getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(a.posts.get()).isEqualTo(1);assertThat(b.posts.get()).isEqualTo(1);
        assertThat(a.saved).containsKey(first.getCommandId()).doesNotContainKey(second.getCommandId());
        assertThat(b.saved).containsKey(second.getCommandId()).doesNotContainKey(first.getCommandId());
        assertThat(a.lastPayload.size()).isEqualTo(7);
        assertThat(a.lastPayload.path("instanceId").asText()).isEqualTo("mc01");
        assertThat(a.lastPayload.path("expectedNodeId").asText()).isEqualTo(a.nodeId);
        assertThat(a.lastPayload.path("expectedStoreId").asText()).isEqualTo(a.storeId);
        assertThat(a.lastPayload.path("expiresAt").asText()).isEqualTo(repository.findCommand(first.getId()).orElseThrow().expiresAt().toString());
        assertThat(a.authFailures.get()+b.authFailures.get()).isZero();
        assertThat(instances.findById("instance-a").orElseThrow().getStatus()).isEqualTo(InstanceStatus.STOPPED);
        assertThat(instances.findById("instance-a").orElseThrow().getUpdatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(tasks.events(first.getId())).extracting(TaskEvent::sequence).containsExactly(1L,2L,3L);
        assertThat(tasks.events(first.getId())).extracting(TaskEvent::toStatus).containsExactly(TaskStatus.PENDING,TaskStatus.DISPATCHING,TaskStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM instance_task_locks",Integer.class)).isZero();
    }

    @Test void concurrentSameKeyReturnsOneTaskAndDifferentContentConflicts() throws Exception {
        String key=UUID.randomUUID().toString();
        CyclicBarrier barrier=new CyclicBarrier(2);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Task> call=()->{barrier.await();return tasks.execute("instance-a","START","MOCK",key,true);};
            Future<Task> one=pool.submit(call),two=pool.submit(call);
            Task original=one.get(10,TimeUnit.SECONDS);
            assertThat(two.get(10,TimeUnit.SECONDS).getId()).isEqualTo(original.getId());
            assertThat(repository.count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_queue",Integer.class)).isEqualTo(1);
            assertThat(tasks.events(original.getId())).hasSize(1);
            assertThatThrownBy(()->tasks.execute("instance-b","START","MOCK",key,true)).isInstanceOf(TaskControlException.class).hasMessage("IDEMPOTENCY_CONFLICT");
            assertThatThrownBy(()->tasks.execute("instance-a","STOP","MOCK",key,true)).hasMessage("IDEMPOTENCY_CONFLICT");
            assertThatThrownBy(()->create("instance-a")).hasMessage("INSTANCE_HAS_UNRESOLVED_TASK");
        } finally {pool.shutdownNow();}
    }

    @Test void queueAndMutexRollbackTogetherWhenEventWriteFails() {
        jdbc.execute("ALTER TABLE task_events ADD CONSTRAINT reject_test_event CHECK (reason<>'ACCEPTED')");
        try {
            assertThatThrownBy(()->create("instance-a")).isInstanceOf(RuntimeException.class);
            assertThat(repository.count()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_queue",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM instance_task_locks",Integer.class)).isZero();
        } finally {jdbc.execute("ALTER TABLE task_events DROP CONSTRAINT reject_test_event");}
    }

    @Test void casLeaseHasOneOwnerAndReclaimedLeaseRejectsLateResult() throws Exception {
        Task task=create("instance-a");
        CyclicBarrier barrier=new CyclicBarrier(2);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Optional<TaskQueueStore.Claim>> call=()->{barrier.await();return store.claim();};
            Future<Optional<TaskQueueStore.Claim>> x=pool.submit(call),y=pool.submit(call);
            List<TaskQueueStore.Claim> claims=new ArrayList<>();
            x.get(10,TimeUnit.SECONDS).ifPresent(claims::add);y.get(10,TimeUnit.SECONDS).ifPresent(claims::add);
            assertThat(claims).hasSize(1);
            TaskQueueStore.Claim old=claims.get(0);
            jdbc.update("UPDATE task_queue SET lease_until=?",Timestamp.from(Instant.EPOCH));
            TaskQueueStore.Claim fresh=store.claim().orElseThrow();
            assertThat(fresh.owner()).isNotEqualTo(old.owner());
            assertThat(store.beginPost(old)).isFalse();
            assertThat(store.finish(old,TaskStatus.SUCCEEDED,"STALE")).isFalse();
            assertThat(store.finish(fresh,TaskStatus.UNKNOWN,"REVIEW_REQUIRED")).isTrue();
            assertThat(store.finish(old,TaskStatus.FAILED,"STALE")).isFalse();
            assertThat(tasks.findById(task.getId()).getStatus()).isEqualTo(TaskStatus.UNKNOWN);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM instance_task_locks",Integer.class)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }

    @Test void lostPostResponseRecoversByGetWithSameCommandAndOneDelivery() {
        a.dropPostResponse=true;
        Task task=create("instance-a");
        assertThat(run(task).getStatus()).isEqualTo(TaskStatus.RETRY_WAIT);
        assertThat(a.posts.get()).isEqualTo(1);
        a.dropPostResponse=false;
        // 替换消费者对象模拟进程内存丢失，全部绑定来自数据库。
        TaskWorker reopened=new TaskWorker(store,new AgentCommandGateway(gatewayProperties,properties,json),
                new TaskControlPolicy(security,gatewayProperties,properties),properties,nodes,instances);
        due();reopened.runOnce();
        Task restored=tasks.findById(task.getId());
        assertThat(restored.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(restored.getAttempts()).isEqualTo(1);
        assertThat(restored.getCommandId()).isEqualTo(task.getCommandId());
        assertThat(a.posts.get()).isEqualTo(1);
    }

    @Test void changedStoreAddressAndWrongResponseIdentityNeverPostAgain() {
        Task task=create("instance-a");
        a.storeId=UUID.randomUUID().toString();
        assertThat(run(task).getStatus()).isEqualTo(TaskStatus.UNKNOWN);
        assertThat(a.posts.get()).isZero();
        Task another=create("instance-b");
        b.wrongIdentity=true;
        assertThat(run(another).getStatus()).isEqualTo(TaskStatus.UNKNOWN);
        assertThat(tasks.findById(another.getId()).getResultCode()).isEqualTo("AGENT_TASK_IDENTITY_MISMATCH");
        worker.runOnce();assertThat(b.posts.get()).isEqualTo(1);
        assertThatThrownBy(()->create("instance-a")).hasMessage("INSTANCE_HAS_UNRESOLVED_TASK");
    }

    @Test void changedTargetAddressStopsBeforeAnyHttpDelivery() {
        Task task=create("instance-a");
        Node node=nodes.findById("node-a").orElseThrow();node.setAddress(b.address());nodes.save(node);
        assertThat(run(task).getResultCode()).isEqualTo("TARGET_CHANGED");
        assertThat(a.posts.get()+b.posts.get()).isZero();
    }

    @Test void agentUnknownRetainsMutexAndDisablesAutomaticRetries() {
        a.resultStatus="UNKNOWN";
        Task task=create("instance-a");
        assertThat(run(task).getStatus()).isEqualTo(TaskStatus.UNKNOWN);
        due();worker.runOnce();assertThat(a.posts.get()).isEqualTo(1);
        assertThatThrownBy(()->create("instance-a")).hasMessage("INSTANCE_HAS_UNRESOLVED_TASK");
    }

    @Test void confirmedReceiptSurvivesRetryAndDisappearingRecordCannotReplay() {
        a.resultStatus="PENDING";
        Task task=create("instance-a");
        assertThat(run(task).getStatus()).isEqualTo(TaskStatus.DELIVERED);
        a.queryHttpStatus=503;
        assertThat(run(task).getStatus()).isEqualTo(TaskStatus.RETRY_WAIT);
        a.queryHttpStatus=404;
        assertThat(run(task).getResultCode()).isEqualTo("AGENT_RECORD_LOST");
        assertThat(a.posts.get()).isEqualTo(1);
    }

    @Test void disablingControlsKeepsExistingResultsQueryableAndStopsNewPost() {
        a.resultStatus="PENDING";
        Task accepted=create("instance-a");run(accepted);
        Task waiting=create("instance-b");
        properties.setControlEnabled(false);
        TaskCommand binding=repository.findCommand(waiting.getId()).orElseThrow();
        assertThat(tasks.execute(waiting.getInstanceId(),waiting.getAction(),waiting.getExecutionMode(),binding.idempotencyKey(),true).getId()).isEqualTo(waiting.getId());
        a.saved.put(accepted.getCommandId(),a.reply(a.lastPayload,"SUCCEEDED"));
        assertThat(run(accepted).getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(run(waiting).getResultCode()).isEqualTo("CONTROL_DISABLED");
        assertThat(b.posts.get()).isZero();
    }

    @Test void expiryRequiresConfirmedAbsenceOtherwiseBecomesUnknown() {
        Task absent=create("instance-a");
        jdbc.update("UPDATE tasks SET expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(10)),absent.getId());
        assertThat(run(absent).getResultCode()).isEqualTo("COMMAND_EXPIRED_ABSENT");
        assertThat(a.posts.get()).isZero();
        Task uncertain=create("instance-b");
        b.queryHttpStatus=503;
        jdbc.update("UPDATE tasks SET expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1000)),uncertain.getId());
        assertThat(run(uncertain).getStatus()).isEqualTo(TaskStatus.UNKNOWN);
        assertThat(b.posts.get()).isZero();
    }

    @Test void fullAgentQueueRetriesOnlySameFixedPayloadAfterAnotherQuery() {
        a.postHttpStatus=429;
        Task task=create("instance-a");
        assertThat(run(task).getStatus()).isEqualTo(TaskStatus.RETRY_WAIT);
        JsonNode original=a.lastPayload.deepCopy();
        a.postHttpStatus=0;
        assertThat(run(task).getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(a.lastPayload).isEqualTo(original);
        assertThat(a.sequence).containsExactly("GET","POST","GET","POST");
        assertThat(tasks.findById(task.getId()).getAttempts()).isEqualTo(2);
    }

    @Test void malformedOrWrongModeResponsesBecomeUnknown() {
        a.badJson=true;
        Task task=create("instance-a");
        assertThat(run(task).getResultCode()).isEqualTo("AGENT_TASK_RESPONSE_INVALID");
        b.mode="DOCKER";
        assertThatThrownBy(()->create("instance-b")).hasMessage("EXECUTION_MODE_CHANGED");
        assertThat(a.posts.get()).isEqualTo(1);assertThat(b.posts.get()).isZero();
    }

    @Test void rejectedPostIsQueriedAgainBeforeReleasingMutex() {
        a.postHttpStatus=403;
        Task task=create("instance-a");
        assertThat(run(task).getStatus()).isEqualTo(TaskStatus.RETRY_WAIT);
        // 错误响应并不能证实代理/先前迟到投递没有被接收。
        a.saved.put(task.getCommandId(),a.reply(a.lastPayload,"SUCCEEDED"));
        assertThat(run(task).getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(a.posts.get()).isEqualTo(1);
    }

    @Test void targetAllowlistAndDockerRequireExplicitPermission() {
        properties.setAllowedInstanceIds(Set.of("instance-a"));
        assertThatThrownBy(()->create("instance-b")).hasMessage("TARGET_NOT_ALLOWED");
        Instance instance=instances.findById("instance-a").orElseThrow();instance.setDataSource("DOCKER");instances.save(instance);a.mode="DOCKER";
        assertThatThrownBy(()->tasks.execute("instance-a","STOP","DOCKER",UUID.randomUUID().toString(),true)).hasMessage("TARGET_NOT_ALLOWED");
        properties.setAllowDocker(true);
        Task task=tasks.execute("instance-a","STOP","DOCKER",UUID.randomUUID().toString(),true);
        assertThat(run(task).getExecutionMode()).isEqualTo("DOCKER");
        assertThat(a.lastPayload.path("expectedExecutionMode").asText()).isEqualTo("DOCKER");
    }

    @Test void nodeDeletionProtectsPendingAndHistoricalTasks() {
        Task task=create("instance-a");
        assertThatThrownBy(()->nodeService.delete("node-a")).hasMessage("NODE_HAS_TASK_HISTORY");
        run(task);
        assertThatThrownBy(()->nodeService.delete("node-a")).hasMessage("NODE_HAS_TASK_HISTORY");
        assertThat(tasks.findById(task.getId()).getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test void apiRolesIdempotencyHttp202EventsCorsAndReadOnly() throws Exception {
        String key=UUID.randomUUID().toString(),path="/api/instances/instance-a/actions";
        String body="{\"action\":\"START\",\"expectedExecutionMode\":\"MOCK\"}";
        assertThat(http("POST",path,null,key,body).statusCode()).isEqualTo(401);
        assertThat(http("POST",path,"test-view",key,body).statusCode()).isEqualTo(403);
        assertThat(http("POST","/api/nodes","test-view",null,"{\"name\":\"blocked\",\"address\":\"127.0.0.1\"}").statusCode()).isEqualTo(403);
        assertThat(http("DELETE","/api/nodes/node-a","test-view",null,null).statusCode()).isEqualTo(403);
        assertThat(json.readTree(http("GET","/api/control/capabilities","test-view",null,null).body()).path("data").path("canControl").asBoolean()).isFalse();
        assertThat(json.readTree(http("GET","/api/control/capabilities","test-operator",null,null).body()).path("data").path("canControl").asBoolean()).isTrue();
        assertThat(http("POST",path,"test-operator",null,body).statusCode()).isEqualTo(400);
        HttpResponse<String> created=http("POST",path,"test-operator",key,body);
        assertThat(created.statusCode()).isEqualTo(202);
        JsonNode task=json.readTree(created.body()).path("data");
        assertThat(task.has("targetAddress")).isFalse();assertThat(task.has("idempotencyKey")).isFalse();assertThat(task.has("storeId")).isFalse();
        HttpResponse<String> replay=http("POST",path,"test-operator",key,body);
        assertThat(replay.statusCode()).isEqualTo(202);
        assertThat(json.readTree(replay.body()).path("data").path("id")).isEqualTo(task.path("id"));
        assertThat(json.readTree(http("GET","/api/tasks/"+task.path("id").asText()+"/events","test-view",null,null).body()).path("data")).hasSize(1);
        HttpRequest cors=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Origin","http://localhost:5173").header("Access-Control-Request-Method","POST")
                .header("Access-Control-Request-Headers","authorization,idempotency-key,content-type")
                .method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> preflight=HttpClient.newHttpClient().send(cors,HttpResponse.BodyHandlers.ofString());
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("access-control-allow-headers").orElse("").toLowerCase()).contains("idempotency-key");
        security.setReadOnly(true);
        assertThat(http("POST",path,"test-operator",key,body).statusCode()).isEqualTo(405);
        assertThat(http("GET","/api/tasks","test-operator",null,null).statusCode()).isEqualTo(200);
    }

    @Test void slowResponseBodyHasTotalDeadlineAndReleasesWorker() throws Exception {
        Task task=create("instance-a");a.stallQueryBody=true;
        ExecutorService caller=Executors.newSingleThreadExecutor();
        long started=System.nanoTime();
        try {
            Future<Task> result=caller.submit(()->run(task));
            assertThat(result.get(4,TimeUnit.SECONDS).getStatus()).isEqualTo(TaskStatus.RETRY_WAIT);
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(4));
            assertThat(a.posts.get()).isZero();
            Task next=create("instance-b");
            assertThat(run(next).getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        } finally {caller.shutdownNow();}
    }

    @Test void successWithoutStartedEvidenceOrMatchingObservationIsUnknown() {
        a.invalidSuccessEvidence=true;
        Task task=create("instance-a");
        assertThat(run(task).getResultCode()).isEqualTo("AGENT_TASK_RESPONSE_INVALID");
        b.resultStatus="PENDING";Task second=create("instance-b");run(second);
        ObjectNode wrong=b.reply(b.lastPayload,"SUCCEEDED");wrong.put("observedStatus","STOPPED");b.saved.put(second.getCommandId(),wrong);
        assertThat(run(second).getStatus()).isEqualTo(TaskStatus.UNKNOWN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM instance_task_locks",Integer.class)).isEqualTo(2);
    }

    HttpResponse<String> http(String method,String path,String token,String key,String body) throws Exception {
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(key!=null)request.header("Idempotency-Key",key);
        request.header("Content-Type","application/json");
        return HttpClient.newHttpClient().send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }

    final class FakeAgent implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor=Executors.newCachedThreadPool();
        final String nodeId;
        volatile String storeId=UUID.randomUUID().toString(),mode="MOCK",resultStatus="SUCCEEDED";
        volatile boolean dropPostResponse,wrongIdentity,badJson,stallQueryBody,invalidSuccessEvidence;
        volatile int queryHttpStatus,postHttpStatus;
        final AtomicInteger posts=new AtomicInteger(),authFailures=new AtomicInteger();
        final Map<String,ObjectNode> saved=new ConcurrentHashMap<>();
        final List<String> sequence=new CopyOnWriteArrayList<>();
        volatile JsonNode lastPayload;
        FakeAgent(String nodeId) throws IOException {
            this.nodeId=nodeId;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/agent/",this::handle);server.setExecutor(executor);server.start();
        }
        String address(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        void handle(HttpExchange exchange) throws IOException {
            String path=exchange.getRequestURI().getPath();
            boolean health=path.equals("/api/agent/health");
            if(!("Bearer "+(health?"test-read":"test-control")).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                authFailures.incrementAndGet();send(exchange,401,"{}");return;
            }
            if(health) {
                send(exchange,200,json.writeValueAsString(Map.of("taskProtocolVersion","1.0","nodeId",nodeId,"storeId",storeId,"executionMode",mode,"controlEnabled",true)));return;
            }
            sequence.add(exchange.getRequestMethod());
            if(stallQueryBody && exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();
                try {new CountDownLatch(1).await(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                exchange.close();return;
            }
            if(exchange.getRequestMethod().equals("POST")) {
                posts.incrementAndGet();lastPayload=json.readTree(exchange.getRequestBody().readAllBytes());
                if(postHttpStatus!=0){send(exchange,postHttpStatus,"{}");return;}
                ObjectNode reply=reply(lastPayload,resultStatus);saved.put(lastPayload.path("commandId").asText(),reply);
                if(dropPostResponse){exchange.close();return;}
                send(exchange,202,badJson?"not-json":reply.toString());return;
            }
            if(queryHttpStatus!=0){send(exchange,queryHttpStatus,"{}");return;}
            ObjectNode found=saved.get(path.substring(path.lastIndexOf('/')+1));
            send(exchange,found==null?404:200,found==null?"{}":found.toString());
        }
        ObjectNode reply(JsonNode payload,String status) {
            ObjectNode out=json.createObjectNode();
            out.put("taskId",payload.path("commandId").asText());out.put("instanceId",wrongIdentity?"wrong":payload.path("instanceId").asText());
            out.put("action",payload.path("action").asText());out.put("status",status);out.put("message","fixed result");
            String time=Instant.now().toString();out.put("createdAt",time);
            if(Set.of("RUNNING","SUCCEEDED").contains(status)&&!invalidSuccessEvidence)out.put("startedAt",time);else out.putNull("startedAt");
            if(Set.of("SUCCEEDED","FAILED","UNKNOWN").contains(status))out.put("finishedAt",time);else out.putNull("finishedAt");
            out.put("executionMode",mode);out.put("nodeId",nodeId);out.put("storeId",storeId);out.put("resultCode",status.equals("SUCCEEDED")?"STATE_CONFIRMED":status);
            out.put("observedStatus",payload.path("action").asText().equals("stop")?"STOPPED":"RUNNING");return out;
        }
        void send(HttpExchange exchange,int status,String body) throws IOException {
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        }
        public void close(){server.stop(0);executor.shutdownNow();}
    }
}
