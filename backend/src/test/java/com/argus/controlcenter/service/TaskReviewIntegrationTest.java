package com.argus.controlcenter.service;

import com.argus.controlcenter.config.*;
import com.argus.controlcenter.domain.*;
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
    "spring.datasource.url=jdbc:h2:mem:review-contract;DB_CLOSE_DELAY=-1","argus.demo-data.enabled=false",
    "argus.security.api-auth-required=true","argus.security.api-access-token=review-view","argus.security.api-control-token=review-operator","argus.security.read-only=false",
    "argus.agent.enabled=true","argus.agent.read-only=true","argus.agent.allow-control=false","argus.agent.require-allowlist=true","argus.agent.allowed-hosts=127.0.0.1","argus.agent.auth-token=agent-view",
    "argus.agent.connect-timeout=1s","argus.agent.read-timeout=1s","argus.agent.sync-initial-delay-ms=86400000",
    "argus.tasks.control-enabled=false","argus.tasks.agent-control-token=agent-operator","argus.tasks.initial-delay-ms=86400000",
    "argus.task-review.enabled=true","argus.task-review.allowed-instance-ids=review-instance","argus.task-review.lease=20s","argus.task-review.initial-delay-ms=86400000"
})
@ActiveProfiles("test")
class TaskReviewIntegrationTest {
    @Autowired TaskReviewService reviews;@Autowired TaskReviewStore store;@Autowired TaskReviewWorker worker;@Autowired TaskReviewGateway gateway;
    @Autowired TaskResolutionRepository resolutions;@Autowired TaskRepository tasks;@Autowired NodeRepository nodes;@Autowired InstanceRepository instances;
    @Autowired TaskService taskService;@Autowired TaskQueueStore taskQueue;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
    @Autowired TaskReviewProperties reviewConfig;@Autowired SecurityProperties security;@Autowired TaskControlProperties taskConfig;@Autowired AgentGatewayProperties gatewayConfig;
    @LocalServerPort int port;
    Fake agent;TaskCommand original;String key;
    final String body="{\"decision\":\"ACKNOWLEDGE_UNCERTAINTY\",\"reason\":\"已核对日志\",\"evidence\":\"逐项核对原始记录与运行状态\",\"acknowledgeNoReplay\":true,\"acknowledgeResidualRisk\":true}";

    @BeforeEach void setup()throws Exception {
        for(String table:List.of("task_resolution_events","task_resolution_queue","task_resolutions","task_events","instance_task_locks","task_queue","tasks","logs","instances","nodes"))jdbc.update("DELETE FROM "+table);
        reviewConfig.setEnabled(true);reviewConfig.setAllowedInstanceIds(Set.of("review-instance"));reviewConfig.setMaxAttempts(5);security.setReadOnly(false);taskConfig.setControlEnabled(false);gatewayConfig.setAllowControl(false);
        agent=new Fake();key=UUID.randomUUID().toString();
        nodes.save(new Node("review-node","node",agent.address(),NodeStatus.ONLINE,Instant.now()));
        Instance instance=new Instance("review-instance","instance","review-node","mc01","test",InstanceStatus.STOPPED,Instant.parse("2026-10-01T00:00:00Z"));
        instance.setAgentInstanceId("mc01");instance.setDataSource("MOCK");instances.save(instance);
        Task task=new Task("review-task","review-instance","RESTART",TaskStatus.UNKNOWN,"CONFIRMATION_EXPIRED",Instant.parse("2026-10-01T00:00:00Z"),Instant.parse("2026-10-01T00:10:00Z"));
        task.setNodeId("review-node");task.setAgentInstanceId("mc01");task.setCommandId(UUID.randomUUID().toString());task.setExecutionMode("MOCK");task.setRequestedBy("operator");task.setUpdatedAt(task.getFinishedAt());task.setResultCode("CONFIRMATION_EXPIRED");task.setAttempts(1);
        original=new TaskCommand(task,UUID.randomUUID().toString(),agent.address(),"agent-node",agent.storeId,Instant.parse("2026-10-01T00:05:00Z"),2,true);
        tasks.insert(original);jdbc.update("UPDATE tasks SET agent_accepted=TRUE WHERE id=?",task.getId());
        jdbc.update("INSERT INTO instance_task_locks(instance_id,task_id) VALUES(?,?)",task.getInstanceId(),task.getId());
        tasks.appendEvent(task.getId(),2,TaskStatus.RUNNING,TaskStatus.UNKNOWN,"worker","CONFIRMATION_EXPIRED",task.getFinishedAt());
        original=tasks.findCommand(task.getId()).orElseThrow();
    }
    @AfterEach void close(){if(agent!=null)agent.close();}
    TaskResolution create(){return reviews.submit(original.task().getId(),key,body,true);}
    TaskResolution run(){jdbc.update("UPDATE task_resolution_queue SET next_run_at=?",Timestamp.from(Instant.EPOCH));worker.runOnce();return reviews.byTask(original.task().getId());}
    void unchanged() {
        Task now=tasks.findById(original.task().getId()).orElseThrow();
        assertThat(now.getStatus()).isEqualTo(TaskStatus.UNKNOWN);assertThat(now.getResultCode()).isEqualTo(original.task().getResultCode());
        assertThat(now.getFinishedAt()).isEqualTo(original.task().getFinishedAt());assertThat(now.getUpdatedAt()).isEqualTo(original.task().getUpdatedAt());assertThat(now.getAttempts()).isEqualTo(1);
        assertThat(tasks.events(now.getId())).hasSize(1);
        assertThat(instances.findById("review-instance").orElseThrow().getStatus()).isEqualTo(InstanceStatus.STOPPED);
        assertThat(agent.actionPosts.get()).isZero();assertThat(agent.authFailures.get()).isZero();
    }

    @Test void independentReviewAppliesWithoutChangingUnknownAndOldReplayCannotRemoveNewLock() {
        assertThat(reviews.evidence("review-task").path("canAcknowledge").booleanValue()).isTrue();
        assertThat(taskService.findById("review-task").isBlocksInstance()).isTrue();
        TaskResolution accepted=create();assertThat(accepted.status()).isEqualTo("PENDING");
        assertThat(run().status()).isEqualTo("APPLIED");unchanged();
        assertThat(taskService.findById("review-task").isBlocksInstance()).isFalse();
        assertThat(taskService.findById("review-task").getReviewSummary().status()).isEqualTo("APPLIED");
        taskConfig.setControlEnabled(true);taskConfig.setAllowedInstanceIds(Set.of("review-instance"));gatewayConfig.setAllowControl(true);
        Task next=taskQueue.enqueue(instances.findById("review-instance").orElseThrow(),agent.address(),"START","MOCK",UUID.randomUUID().toString(),new AgentCommandGateway.Health("agent-node",agent.storeId,"MOCK",true));
        assertThat(create().id()).isEqualTo(accepted.id());assertThat(agent.reviewPosts.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT task_id FROM instance_task_locks WHERE instance_id='review-instance'",String.class)).isEqualTo(next.getId());
        assertThat(reviews.events(accepted.id())).extracting(TaskResolution.Event::sequence).containsExactly(1L,2L,3L);
    }
    @Test void lostAcknowledgementRecoversByOriginalReceiptWithoutAnotherPost() {
        agent.dropReply=true;create();assertThat(run().status()).isEqualTo("RETRY_WAIT");
        assertThat(store.ownsInstance(original)).isTrue();assertThat(agent.receipt).isNotNull();
        assertThat(run().status()).isEqualTo("APPLIED");assertThat(agent.reviewPosts.get()).isEqualTo(1);unchanged();
    }
    @Test void unknownWithVerifiedAgentTerminalKeepsOriginalExecutionHistory() {
        agent.taskStatus="SUCCEEDED";create();TaskResolution result=run();assertThat(result.status()).isEqualTo("APPLIED");
        assertThat(result.agentEvidence().path("classification").asText()).isEqualTo("CONFIRMED_TERMINAL");
        assertThat(result.agentEvidence().path("taskStatus").asText()).isEqualTo("SUCCEEDED");unchanged();
    }
    @Test void activeExecutorBlocksAndExactSameIntentCanResumeLater() {
        agent.active=true;JsonNode before=reviews.evidence("review-task");assertThat(before.path("classification").asText()).isEqualTo("EXECUTOR_ACTIVE");assertThat(before.path("canAcknowledge").booleanValue()).isFalse();
        TaskResolution first=create();assertThat(run().status()).isEqualTo("BLOCKED");assertThat(store.ownsInstance(original)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_resolution_queue",Integer.class)).isZero();
        agent.active=false;TaskResolution resumed=create();assertThat(resumed.id()).isEqualTo(first.id());assertThat(resumed.status()).isEqualTo("PENDING");
        assertThat(run().status()).isEqualTo("APPLIED");assertThat(reviews.events(first.id())).extracting(TaskResolution.Event::reason).contains("REVIEW_RECHECK_REQUESTED");unchanged();
    }
    @Test void missingRecordAndChangedStoreNeverReleaseAndOriginalBindingCanResume() {
        create();agent.missing=true;assertThat(reviews.evidence("review-task").path("classification").asText()).isEqualTo("RECORD_MISSING");
        assertThat(run().status()).isEqualTo("BLOCKED");assertThat(agent.reviewPosts.get()).isZero();
        agent.missing=false;String old=agent.storeId;agent.storeId=UUID.randomUUID().toString();create();
        assertThat(run().resultCode()).isEqualTo("AGENT_BINDING_CHANGED");assertThat(store.ownsInstance(original)).isTrue();
        agent.storeId=old;create();assertThat(run().status()).isEqualTo("APPLIED");unchanged();
    }
    @Test void addressChangeDoesNotRedirectReviewAndCanResumeAfterRestoringOriginalAddress() {
        create();Node node=nodes.findById("review-node").orElseThrow();node.setAddress("http://127.0.0.1:1");nodes.save(node);
        assertThat(run().resultCode()).isEqualTo("TARGET_CHANGED");assertThat(agent.reviewPosts.get()).isZero();
        assertThat(reviews.evidence("review-task").path("bindingMatches").booleanValue()).isFalse();
        node.setAddress(agent.address());nodes.save(node);create();assertThat(run().status()).isEqualTo("APPLIED");unchanged();
    }
    @Test void invalidReceiptAndContradictorySuccessRemainBlocked() {
        agent.badHash=true;create();assertThat(run().status()).isEqualTo("BLOCKED");assertThat(store.ownsInstance(original)).isTrue();
        agent.receipt=null;agent.badHash=false;agent.taskStatus="SUCCEEDED";agent.badSuccess=true;create();
        assertThat(run().status()).isEqualTo("BLOCKED");assertThat(store.ownsInstance(original)).isTrue();unchanged();
    }
    @Test void maximumTransientAttemptsAreResumableWithoutNewIdentity() {
        reviewConfig.setMaxAttempts(2);agent.unavailable=true;TaskResolution first=create();assertThat(run().status()).isEqualTo("RETRY_WAIT");
        assertThat(run().status()).isEqualTo("BLOCKED");assertThat(store.ownsInstance(original)).isTrue();
        agent.unavailable=false;assertThat(create().id()).isEqualTo(first.id());TaskResolution end=run();assertThat(end.status()).isEqualTo("APPLIED");assertThat(end.attempts()).isEqualTo(3);
    }
    @Test void disablingReviewAfterAgentAcceptsLeavesCentralLockUntilExplicitSameKeyRecheck() {
        agent.afterPost=()->reviewConfig.setEnabled(false);create();assertThat(run().status()).isEqualTo("BLOCKED");assertThat(store.ownsInstance(original)).isTrue();
        reviewConfig.setEnabled(true);agent.afterPost=()->{};create();assertThat(run().status()).isEqualTo("APPLIED");assertThat(agent.reviewPosts.get()).isEqualTo(1);unchanged();
    }
    @Test void concurrentSameKeyIsUniqueAndChangedIntentConflicts()throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CyclicBarrier barrier=new CyclicBarrier(2);
        try {
            Callable<TaskResolution> submit=()->{barrier.await();return create();};Future<TaskResolution> one=pool.submit(submit),two=pool.submit(submit);
            assertThat(one.get(10,TimeUnit.SECONDS).id()).isEqualTo(two.get(10,TimeUnit.SECONDS).id());
        }finally{pool.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_resolutions",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_resolution_queue",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->reviews.submit("review-task",key,body.replace("已核对日志","另一个决定"),true)).hasMessage("REVIEW_IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->reviews.submit("review-task",UUID.randomUUID().toString(),body,true)).hasMessage("TASK_ALREADY_HAS_RESOLUTION");
    }
    @Test void leaseCompetitionAndLateOwnerCannotApply()throws Exception {
        create();ExecutorService pool=Executors.newFixedThreadPool(2);CyclicBarrier barrier=new CyclicBarrier(2);List<TaskReviewStore.Claim> claims=new ArrayList<>();
        try {
            Callable<Optional<TaskReviewStore.Claim>> claim=()->{barrier.await();return store.claim();};Future<Optional<TaskReviewStore.Claim>> a=pool.submit(claim),b=pool.submit(claim);
            a.get(10,TimeUnit.SECONDS).ifPresent(claims::add);b.get(10,TimeUnit.SECONDS).ifPresent(claims::add);
        }finally{pool.shutdownNow();}
        assertThat(claims).hasSize(1);TaskReviewStore.Claim stale=claims.get(0);
        jdbc.update("UPDATE task_resolution_queue SET lease_until=?",Timestamp.from(Instant.EPOCH));TaskReviewStore.Claim fresh=store.claim().orElseThrow();
        assertThat(store.apply(stale,json.createObjectNode())).isFalse();assertThat(store.beforePost(stale)).isFalse();
        worker.process(fresh);assertThat(reviews.byTask("review-task").status()).isEqualTo("APPLIED");unchanged();
    }
    @Test void transactionFailureRestoresOriginalLockThenReceiptCanRecover() {
        create();jdbc.execute("ALTER TABLE task_resolution_events ADD CONSTRAINT reject_applied CHECK (reason<>'REVIEW_APPLIED')");
        try {assertThatThrownBy(this::run).isInstanceOf(RuntimeException.class);assertThat(store.ownsInstance(original)).isTrue();assertThat(reviews.byTask("review-task").status()).isEqualTo("PROCESSING");}
        finally{jdbc.execute("ALTER TABLE task_resolution_events DROP CONSTRAINT reject_applied");}
        jdbc.update("UPDATE task_resolution_queue SET lease_until=?",Timestamp.from(Instant.EPOCH));assertThat(run().status()).isEqualTo("APPLIED");assertThat(agent.reviewPosts.get()).isEqualTo(1);unchanged();
    }
    @Test void initialIntentAndOutboxRollbackTogether() {
        jdbc.execute("ALTER TABLE task_resolution_events ADD CONSTRAINT reject_review CHECK (reason<>'REVIEW_ACCEPTED')");
        try{assertThatThrownBy(this::create).isInstanceOf(RuntimeException.class);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_resolutions",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_resolution_queue",Integer.class)).isZero();assertThat(store.ownsInstance(original)).isTrue();}
        finally{jdbc.execute("ALTER TABLE task_resolution_events DROP CONSTRAINT reject_review");}
    }
    @Test void authorityCoversOldBlockerOutsideRecentHundredTasks() {
        for(int n=0;n<101;n++){Task t=new Task("history-"+n,"review-instance","START",TaskStatus.SUCCEEDED,"legacy",Instant.now(),Instant.now());tasks.save(t);}
        taskConfig.setControlEnabled(true);taskConfig.setAllowedInstanceIds(Set.of("review-instance"));gatewayConfig.setAllowControl(true);
        assertThat(taskService.findAll()).hasSize(100).noneMatch(t->t.getId().equals("review-task"));
        var target=taskService.capabilities(true).targets().get(0);assertThat(target.blockingTaskId()).isEqualTo("review-task");assertThat(target.allowedActions()).isEmpty();
        create();run();assertThat(taskService.capabilities(true).targets().get(0).blockingTaskId()).isNull();assertThat(taskService.capabilities(true).targets().get(0).allowedActions()).contains("RESTART");
    }
    @Test void apiPermissionsStrictJsonAndReadOnlyAreEnforced()throws Exception {
        assertThat(api("review-view",body).statusCode()).isEqualTo(403);
        security.setReadOnly(true);assertThat(api("review-operator",body).statusCode()).isEqualTo(405);security.setReadOnly(false);
        reviewConfig.setEnabled(false);assertThat(api("review-operator",body).statusCode()).isEqualTo(403);reviewConfig.setEnabled(true);
        assertThat(api("review-operator",body.replace("true","\"true\"")).statusCode()).isEqualTo(400);
        assertThat(api("review-operator",body.replace("{","{\"reason\":\"duplicate\",")).statusCode()).isEqualTo(400);
        assertThat(api("review-operator",body.replace("{","{\"actor\":\"fake\",")).statusCode()).isEqualTo(400);
        var accepted=api("review-operator",body);assertThat(accepted.statusCode()).isEqualTo(202);JsonNode response=json.readTree(accepted.body()).path("data");
        assertThat(response.path("requestKey").asText()).isEqualTo(key);assertThat(response.path("commandId").asText()).isEqualTo(original.task().getCommandId());
        assertThat(response.has("targetAddress")).isFalse();assertThat(response.path("agentEvidence").isNull()).isTrue();
    }
    @Test void prefixHashAgreesWithIndependentUnicodeFixture() {
        assertThat(ResolutionHash.prefixHash(List.of("argus-resolution-v1","11111111-1111-4111-8111-111111111111","22222222-2222-4222-8222-222222222222","sample","restart","node-test","33333333-3333-4333-8333-333333333333","MOCK","2026-10-01T00:00:00Z",ReviewRequest.DECISION,"已核对日志\n未重放原命令 😀","a".repeat(64),"true","true")))
                .isEqualTo("21b2315a0d079c34e3670f94a1eee3ad1b1d9a26f108873faa398bc5d49c9354");
        assertThat(ResolutionHash.prefixHash(List.of("ab","c"))).isNotEqualTo(ResolutionHash.prefixHash(List.of("a","bc")));
    }
    @Test void unicodeTextNormalizationMatchesBrowserAndKeepsInteriorEvidence() {
        ReviewRequest value=new ReviewRequest(ReviewRequest.DECISION,"\u00a0\ufeff\u3000已核对\r\n😀\u3000", "\ufeff证据\u00a0内部\r末尾\ufeff",true,true);
        assertThat(value.reason()).isEqualTo("已核对\n😀");assertThat(value.evidence()).isEqualTo("证据\u00a0内部\n末尾");
        assertThat(new ReviewRequest(ReviewRequest.DECISION,"\u001c保留\u001c","证据",true,true).reason()).isEqualTo("\u001c保留\u001c");
        assertThat(new ReviewRequest(ReviewRequest.DECISION,"😀".repeat(500),"证据",true,true).reason().codePointCount(0,1000)).isEqualTo(500);
        assertThatThrownBy(()->new ReviewRequest(ReviewRequest.DECISION,"😀".repeat(501),"证据",true,true)).hasMessage("REVIEW_TEXT_LENGTH_INVALID");
        assertThatThrownBy(()->new ReviewRequest(ReviewRequest.DECISION,"\u00a0\ufeff\u3000","证据",true,true)).hasMessage("REVIEW_TEXT_LENGTH_INVALID");
        assertThatThrownBy(()->new ReviewRequest(ReviewRequest.DECISION,"\ud800","证据",true,true)).hasMessage("REVIEW_TEXT_INVALID");
    }
    @Test void receiptsCannotObserveOrCloseBeforeOriginalTaskFinishes() {
        agent.earlyObservation=true;create();assertThat(run().status()).isEqualTo("BLOCKED");assertThat(store.ownsInstance(original)).isTrue();
        agent.receipt=null;agent.earlyObservation=false;agent.earlyClosure=true;create();assertThat(run().status()).isEqualTo("BLOCKED");assertThat(store.ownsInstance(original)).isTrue();unchanged();
    }
    @Test void allTargetsLockedStillAllowOriginalKeyConfirmationButNeverNewCommands() {
        taskConfig.setControlEnabled(true);taskConfig.setAllowedInstanceIds(Set.of("review-instance"));gatewayConfig.setAllowControl(true);
        var caps=taskService.capabilities(true);assertThat(caps.canControl()).isTrue();assertThat(caps.allowedActions()).contains("RESTART");
        assertThat(caps.targets()).allSatisfy(target->{assertThat(target.canConfirmPending()).isTrue();assertThat(target.allowedActions()).isEmpty();assertThat(target.blockingTaskId()).isEqualTo("review-task");});
        assertThat(taskService.execute("review-instance","RESTART","MOCK",original.idempotencyKey(),true).getId()).isEqualTo("review-task");
        taskConfig.setAllowedInstanceIds(Set.of());assertThat(taskService.capabilities(true).canControl()).isFalse();assertThat(taskService.capabilities(true).targets().get(0).canConfirmPending()).isFalse();
        assertThat(taskService.capabilities(false).targets().get(0).canConfirmPending()).isFalse();unchanged();
    }
    @Test void blockedRecheckAndStaleWorkerCannotDeadlockOrCreateDuplicateQueue()throws Exception {
        create();TaskReviewStore.Claim stale=store.claim().orElseThrow();assertThat(store.failure(stale,"TEST_BLOCKED",false)).isTrue();
        ExecutorService pool=Executors.newFixedThreadPool(2);CyclicBarrier barrier=new CyclicBarrier(2);
        try {
            Future<TaskResolution> submit=pool.submit(()->{barrier.await();return create();});
            Future<Boolean> late=pool.submit(()->{barrier.await();return store.beforePost(stale);});
            assertThat(submit.get(10,TimeUnit.SECONDS).status()).isEqualTo("PENDING");assertThat(late.get(10,TimeUnit.SECONDS)).isFalse();
        }finally{pool.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_resolution_queue",Integer.class)).isEqualTo(1);
        assertThat(run().status()).isEqualTo("APPLIED");unchanged();
    }
    HttpResponse<String> api(String token,String request)throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/tasks/review-task/resolutions")).header("Authorization","Bearer "+token).header("Idempotency-Key",key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(request)).build(),HttpResponse.BodyHandlers.ofString());
    }

    class Fake implements AutoCloseable {
        final HttpServer server;final ExecutorService executor=Executors.newCachedThreadPool();
        final AtomicInteger reviewPosts=new AtomicInteger(),actionPosts=new AtomicInteger(),authFailures=new AtomicInteger();
        String storeId=UUID.randomUUID().toString(),taskStatus="UNKNOWN";boolean active,missing,unavailable,dropReply,badHash,badSuccess,earlyObservation,earlyClosure;JsonNode receipt;Runnable afterPost=()->{};
        Fake()throws IOException{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);server.createContext("/",this::handle);server.start();}
        String address(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        void handle(HttpExchange x)throws IOException {
            String path=x.getRequestURI().getPath();String token=path.endsWith("/health")?"agent-view":"agent-operator";
            if(!("Bearer "+token).equals(x.getRequestHeaders().getFirst("Authorization"))){authFailures.incrementAndGet();send(x,401,json.createObjectNode());return;}
            if(unavailable){send(x,503,json.createObjectNode());return;}
            if(path.endsWith("/health")) {ObjectNode h=json.createObjectNode();h.put("nodeId","agent-node");h.put("storeId",storeId);h.put("executionMode","MOCK");h.put("taskProtocolVersion","1.0");h.put("controlEnabled",false);h.put("reviewProtocolVersion","1.0");h.put("reviewEnabled",true);send(x,200,h);return;}
            if(path.equals("/api/agent/tasks")&&x.getRequestMethod().equals("POST")){actionPosts.incrementAndGet();send(x,500,json.createObjectNode());return;}
            if(path.endsWith("/review-evidence")) {
                if(missing){send(x,404,json.createObjectNode());return;}
                ObjectNode e=json.createObjectNode();e.put("commandId",original.task().getCommandId());e.put("nodeId","agent-node");e.put("storeId",storeId);e.put("executionMode","MOCK");e.put("checkedAt",Instant.now().toString());e.set("task",taskView());e.put("reviewEnabled",true);e.put("instanceAllowed",true);e.put("workerActive",active);e.put("knownProcessesActive",active);e.put("lockDisposition",taskStatus.equals("UNKNOWN")?"OWNED_BY_COMMAND":"NONE");e.put("processAssessment","CURRENT_PROCESS_CLEARED");send(x,200,e);return;
            }
            if(path.contains("/resolutions/")&&x.getRequestMethod().equals("GET")){send(x,receipt==null?404:200,receipt==null?json.createObjectNode():receipt);return;}
            if(path.endsWith("/resolutions")&&x.getRequestMethod().equals("POST")) {
                reviewPosts.incrementAndGet();if(active){send(x,409,json.createObjectNode());return;}
                JsonNode input=json.readTree(x.getRequestBody().readAllBytes());ObjectNode r=json.createObjectNode();
                for(String f:List.of("resolutionId","instanceId","action","decision"))r.set(f,input.get(f));
                r.put("commandId",original.task().getCommandId());r.put("nodeId","agent-node");r.put("storeId",storeId);r.put("executionMode","MOCK");r.put("status","CLOSED");
                List<String> hash=new ArrayList<>(List.of("argus-resolution-v1",original.task().getCommandId()));
                for(String f:List.of("resolutionId","instanceId","action","expectedNodeId","expectedStoreId","expectedExecutionMode","expectedCommandExpiresAt","decision","reason","evidenceDigest","acknowledgeNoReplay","acknowledgeResidualRisk"))hash.add(input.path(f).asText());
                r.put("requestHash",badHash?"0".repeat(64):ResolutionHash.prefixHash(hash));String now=Instant.now().toString();r.put("createdAt",now);r.put("classification",taskStatus.equals("UNKNOWN")?"ACKNOWLEDGED_UNKNOWN":"CONFIRMED_TERMINAL");r.set("task",taskView());r.put("processAssessment","CURRENT_PROCESS_CLEARED");r.put("observationStatus","AVAILABLE");r.put("observedInstanceStatus","RUNNING");r.put("observedAt",now);receipt=r;
                if(earlyObservation)r.put("observedAt","2026-10-01T00:00:01Z");
                if(earlyClosure){r.put("observedAt","2026-10-01T00:00:01Z");r.put("createdAt","2026-10-01T00:00:01Z");}
                afterPost.run();if(dropReply){dropReply=false;x.close();return;}send(x,201,r);return;
            }
            send(x,missing?404:200,missing?json.createObjectNode():taskView());
        }
        ObjectNode taskView() {
            ObjectNode t=json.createObjectNode();t.put("taskId",original.task().getCommandId());t.put("instanceId","mc01");t.put("action","restart");t.put("status",taskStatus);t.put("message","test-result");
            t.put("createdAt","2026-10-01T00:00:00Z");t.put("startedAt","2026-10-01T00:00:01Z");t.put("finishedAt","2026-10-01T00:00:02Z");t.put("executionMode","MOCK");t.put("nodeId","agent-node");t.put("storeId",storeId);t.put("resultCode",taskStatus.equals("UNKNOWN")?"EXECUTION_UNCERTAIN":"STATE_CONFIRMED");
            if(taskStatus.equals("SUCCEEDED"))t.put("observedStatus",badSuccess?"STOPPED":"RUNNING");else t.putNull("observedStatus");return t;
        }
        void send(HttpExchange x,int code,JsonNode body)throws IOException{byte[] bytes=json.writeValueAsBytes(body);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(code,bytes.length);x.getResponseBody().write(bytes);x.close();}
        public void close(){server.stop(0);executor.shutdownNow();}
    }
}
