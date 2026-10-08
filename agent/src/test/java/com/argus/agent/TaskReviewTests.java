package com.argus.agent;

import com.argus.agent.command.*;
import com.argus.agent.model.*;
import com.argus.agent.service.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 人工核对故障测试：原任务证据不可变，所有外部进程只是本 JDK 的受控测试 helper。 */
public final class TaskReviewTests {
    private static Path root;
    private static int passed, failed;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]).toAbsolutePath().resolve("review-tests-" + System.nanoTime()); Files.createDirectories(root);
        test("review request requires twelve strict fields and two boolean acknowledgements", TaskReviewTests::strictRequest);
        test("reason uses exact ECMA trim boundaries without changing persisted Unicode text", TaskReviewTests::reasonWhitespace);
        test("length-prefixed request hash matches independent Unicode fixture", () -> {
            ResolutionRequest value = vector();
            equal("21b2315a0d079c34e3670f94a1eee3ad1b1d9a26f108873faa398bc5d49c9354", value.requestHash());
            equal(value, ResolutionRequest.parse(value.commandId(), value.json()));
            Files.writeString(root.getParent().resolve("review-hash-request.json"), value.json());
            Files.writeString(root.getParent().resolve("review-hash.txt"), value.requestHash());
        });
        test("receipt refuses observation earlier than original finished evidence", () -> receiptTime(false));
        test("future original finished evidence cannot be closed by a current receipt", () -> receiptTime(true));
        test("review configuration defaults off and requires explicit independent credentials and allowlist", () -> {
            Path dir = folder("config");
            AgentConfig base = TaskInboxTests.config(dir, false, true, 100, false);
            require(!base.reviewEnabled() && base.reviewInstances().isEmpty(), "default review opened");
            for (int variant = 0; variant < 4; variant++) {
                int v = variant;
                try {
                    new AgentConfig(0,"node-test","test",v == 0 ? "" : TaskInboxTests.READ,true,"never-docker","sample","sample",2,2,8192,4,false,false,
                            "localhost",1048576,16,v == 1 ? "" : v == 2 ? TaskInboxTests.READ : TaskInboxTests.CONTROL,Set.of("sample"),dir,100,true,
                            "127.0.0.1",true,v == 3 ? Set.of() : Set.of("sample"));
                    throw new AssertionError("unsafe review config accepted");
                } catch (IllegalArgumentException expected) { }
            }
        });
        test("UNKNOWN closure preserves exact original record and expired original deadline", () -> {
            Path dir = folder("immutable"); Seed seed = seed(dir, "UNKNOWN", true);
            byte[] original = Files.readAllBytes(dir.resolve(seed.command.commandId() + ".task"));
            Counter actions = new Counter();
            try (TaskService service = new TaskService(config(dir, false, true, 100, Set.of("sample")), actions)) {
                ReviewEvidence before = service.reviewEvidence(seed.command.commandId());
                equal("OWNED_BY_COMMAND", before.lockDisposition()); equal("PRIOR_PROCESS_UNVERIFIED", before.processAssessment());
                var result = service.resolve(request(seed.command));
                require(result.created(), "first resolution was not created"); equal("ACKNOWLEDGED_UNKNOWN", result.receipt().classification());
                equal(seed.task, result.receipt().task()); equal(seed.task, service.get(seed.command.commandId()));
                equal("NONE", service.reviewEvidence(seed.command.commandId()).lockDisposition()); equal(0, actions.calls.get());
                require(Arrays.equals(original, Files.readAllBytes(dir.resolve(seed.command.commandId() + ".task"))), "original record bytes changed");
                Files.writeString(root.getParent().resolve("review-contract.json"), result.receipt().json());
                Files.writeString(root.getParent().resolve("review-evidence-contract.json"), before.json());
            }
        });
        test("concurrent duplicate review records once and observes once", () -> {
            Path dir = folder("concurrent"); Seed seed = seed(dir, "UNKNOWN", false); Counter actions = new Counter();
            try (TaskService service = new TaskService(config(dir, false, true, 100, Set.of("sample")), actions)) {
                ResolutionRequest request = request(seed.command);
                ExecutorService clients = Executors.newFixedThreadPool(8);
                try {
                    List<Future<TaskService.ResolutionSubmission>> futures = new ArrayList<>();
                    for (int i=0;i<24;i++) futures.add(clients.submit(() -> service.resolve(request)));
                    int created=0; ResolutionReceipt receipt=null;
                    for (var future:futures) { var result=future.get(5,TimeUnit.SECONDS); if(result.created())created++; if(receipt==null)receipt=result.receipt(); else equal(receipt,result.receipt()); }
                    equal(1,created); equal(1,actions.observations.get());
                } finally { clients.shutdownNow(); }
            }
        });
        test("payload conflict and alternative resolution cannot replace original closure", () -> {
            Path dir=folder("conflict");Seed seed=seed(dir,"UNKNOWN",false);
            try(TaskService service=new TaskService(config(dir,false,true,100,Set.of("sample")),new Counter())) {
                ResolutionRequest original=request(seed.command);service.resolve(original);
                rejected(409,"resolution_conflict",()->service.resolve(copy(original,original.resolutionId(),"changed",original.expectedStoreId(),original.expectedNodeId(),original.expectedExecutionMode())));
                rejected(409,"resolution_conflict",()->service.resolve(copy(original,UUID.randomUUID().toString(),original.reason(),original.expectedStoreId(),original.expectedNodeId(),original.expectedExecutionMode())));
            }
        });
        test("old task and resolution retries cannot unlock a new task", () -> {
            Path dir=folder("new-lock");Seed seed=seed(dir,"UNKNOWN",false);ResolutionRequest request=request(seed.command);
            try(TaskService service=new TaskService(config(dir,true,true,100,Set.of("sample")),new Counter())) {
                ResolutionReceipt receipt=service.resolve(request).receipt();
                TaskCommand next=TaskInboxTests.request(service,"sample","start");service.submit(next);
                equal(receipt,service.resolve(request).receipt()); equal(receipt,service.resolution(request.commandId(),request.resolutionId()));
                equal(seed.task,service.submit(seed.command).task());equal("OWNED_BY_OTHER",service.reviewEvidence(seed.command.commandId()).lockDisposition());
                rejected(409,"instance_busy",()->service.submit(TaskInboxTests.request(service,"sample","stop")));
            }
            try(TaskService reopened=new TaskService(config(dir,true,true,100,Set.of("sample")),new Counter())) {
                equal("UNKNOWN",reopened.get(seed.command.commandId()).status());reopened.resolve(request);
                rejected(409,"instance_busy",()->reopened.submit(TaskInboxTests.request(reopened,"sample","restart")));
            }
        });
        test("full inbox still permits one closure without deleting original command", () -> {
            Path dir=folder("capacity");Seed seed=seed(dir,"UNKNOWN",false);
            try(TaskService service=new TaskService(config(dir,true,true,1,Set.of("sample")),new Counter())) {
                service.resolve(request(seed.command));equal(seed.task,service.get(seed.command.commandId()));
                rejected(503,"inbox_capacity",()->service.submit(TaskInboxTests.request(service,"sample","start")));
            }
        });
        test("review can run with task control disabled but disabled review permits only receipt GET", () -> {
            Path dir=folder("switch");Seed seed=seed(dir,"UNKNOWN",false);ResolutionRequest request=request(seed.command);ResolutionReceipt receipt;
            try(TaskService service=new TaskService(config(dir,false,true,100,Set.of("sample")),new Counter())) {
                require(!service.controlEnabled()&&service.reviewEnabled(),"switches coupled");receipt=service.resolve(request).receipt();
                rejected(403,"control_disabled",()->service.submit(seed.command));
            }
            Counter actions=new Counter();
            try(TaskService service=new TaskService(config(dir,false,false,100,Set.of()),actions)) {
                rejected(403,"review_disabled",()->service.resolve(request));equal(receipt,service.resolution(request.commandId(),request.resolutionId()));equal(0,actions.observations.get());
            }
        });
        test("duplicate review POST honors changed allowlist closing and unhealthy Inbox", TaskReviewTests::duplicatePermission);
        test("review off and allowlist denial retain original lock", () -> {
            for(boolean enabled:List.of(false,true)) {
                Path dir=folder("denied");Seed seed=seed(dir,"UNKNOWN",false);
                try(TaskService service=new TaskService(config(dir,false,enabled,100,enabled?Set.of("other"):Set.of()),new Counter())) {
                    rejected(403,enabled?"review_instance_not_allowed":"review_disabled",()->service.resolve(request(seed.command)));
                    equal("OWNED_BY_COMMAND",service.reviewEvidence(seed.command.commandId()).lockDisposition());
                }
            }
        });
        test("node store mode deadline and action mismatch refuse closure", () -> {
            Path dir=folder("identity");Seed seed=seed(dir,"UNKNOWN",false);ResolutionRequest request=request(seed.command);
            try(TaskService service=new TaskService(config(dir,false,true,100,Set.of("sample")),new Counter())) {
                for(ResolutionRequest changed:List.of(copy(request,request.resolutionId(),request.reason(),UUID.randomUUID().toString(),request.expectedNodeId(),"MOCK"),
                        copy(request,request.resolutionId(),request.reason(),request.expectedStoreId(),"other-node","MOCK"),
                        copy(request,request.resolutionId(),request.reason(),request.expectedStoreId(),request.expectedNodeId(),"DOCKER"),
                        new ResolutionRequest(request.commandId(),request.resolutionId(),"sample","stop",request.expectedNodeId(),request.expectedStoreId(),"MOCK",request.expectedCommandExpiresAt(),request.decision(),request.reason(),request.evidenceDigest(),true,true),
                        new ResolutionRequest(request.commandId(),request.resolutionId(),"sample","restart",request.expectedNodeId(),request.expectedStoreId(),"MOCK",Instant.now().toString(),request.decision(),request.reason(),request.evidenceDigest(),true,true)))
                    rejected(409,"review_target_mismatch",()->service.resolve(changed));
                equal("OWNED_BY_COMMAND",service.reviewEvidence(seed.command.commandId()).lockDisposition());
            }
        });
        test("missing command does not fabricate closure", () -> {
            try(TaskService service=new TaskService(config(folder("absent"),false,true,100,Set.of("sample")),new Counter())) {
                rejected(404,"task_not_found",()->service.resolve(request(TaskInboxTests.request(service,"sample","restart"))));
            }
        });
        test("PENDING is never manually released", () -> {
            Path dir=folder("pending");Seed seed=seed(dir,"PENDING",false);
            try(TaskService service=new TaskService(config(dir,false,true,100,Set.of("sample")),new Counter())) {
                rejected(409,"review_task_not_closed",()->service.resolve(request(seed.command)));
            }
        });
        test("RUNNING worker must exit before UNKNOWN review", TaskReviewTests::activeWorker);
        test("persisted UNKNOWN with active registered scope is refused", () -> {
            Path dir=folder("unknown-scope");Seed seed=seed(dir,"UNKNOWN",false);Counter actions=new Counter();
            try(TaskService service=new TaskService(config(dir,false,true,100,Set.of("sample")),actions)) {
                try(var scope=actions.registry.open(seed.command.commandId())) {
                    equal("UNKNOWN",service.get(seed.command.commandId()).status());require(service.reviewEvidence(seed.command.commandId()).workerActive(),"active scope hidden");
                    rejected(409,"review_executor_active",()->service.resolve(request(seed.command)));
                }
                service.resolve(request(seed.command));
            }
        });
        test("known live helper blocks review after worker has returned UNKNOWN", () -> liveResource(false));
        test("known live reader blocks review after helper and worker exited", () -> liveResource(true));
        test("current observation leftover helper also prevents receipt", TaskReviewTests::observationResource);
        test("unavailable current observation stays null and does not invent historical success", () -> {
            Path dir=folder("unavailable");Seed seed=seed(dir,"UNKNOWN",false);Counter actions=new Counter(){@Override public String observe(String id){throw new IllegalStateException("PRIVATE_OBSERVATION_FAILURE");}};
            try(TaskService service=new TaskService(config(dir,false,true,100,Set.of("sample")),actions)) {
                ResolutionReceipt receipt=service.resolve(request(seed.command)).receipt();equal("UNAVAILABLE",receipt.observationStatus());equal(null,receipt.observedInstanceStatus());equal(seed.task,receipt.task());
                require(!receipt.json().contains("PRIVATE_OBSERVATION_FAILURE"),"unsafe error leaked");
            }
        });
        test("changed target alias cannot close old command", () -> {
            Path dir=folder("alias");Seed seed=seed(dir,"UNKNOWN",false);Counter actions=new Counter();actions.target="another";
            try(TaskService service=new TaskService(config(dir,false,true,100,Set.of("sample")),actions)) {
                rejected(409,"review_target_mismatch",()->service.resolve(request(seed.command)));equal("OWNED_BY_COMMAND",service.reviewEvidence(seed.command.commandId()).lockDisposition());
            }
        });
        test("same-store durable terminal compensation preserves original result", () -> {
            for(String status:List.of("SUCCEEDED","FAILED")) {
                Path dir=folder("terminal");Seed seed=seed(dir,status,false);
                try(TaskService service=new TaskService(config(dir,false,true,100,Set.of("sample")),new Counter())) {
                    var receipt=service.resolve(request(seed.command)).receipt();equal("CONFIRMED_TERMINAL",receipt.classification());equal(seed.task,receipt.task());equal("NONE",service.reviewEvidence(seed.command.commandId()).lockDisposition());
                }
            }
        });
        test("terminal compensation cannot remove another command lock", () -> {
            Path dir=folder("terminal-busy");Seed seed=seed(dir,"SUCCEEDED",false);
            try(TaskService service=new TaskService(config(dir,true,true,100,Set.of("sample")),new Counter())) {
                service.submit(TaskInboxTests.request(service,"sample","stop"));
                rejected(409,"review_lock_mismatch",()->service.resolve(request(seed.command)));
                rejected(409,"instance_busy",()->service.submit(TaskInboxTests.request(service,"sample","start")));
            }
        });
        test("atomic receipt failure preserves original evidence and disables new writes", TaskReviewTests::writeFailure);
        test("corrupt receipt checksum refuses restart", () -> corrupt("checksum"));
        test("receipt with false hash refuses restart despite valid outer checksum", () -> corrupt("hash"));
        test("receipt missing original task refuses restart", () -> corrupt("orphan"));
        test("missing store with receipt cannot generate replacement identity", () -> corrupt("store"));
        test("same resolution ID cannot be claimed by another command", TaskReviewTests::sameResolutionId);
        test("real Java crash before receipt commit retains UNKNOWN lock", () -> crash("before"));
        test("real Java crash after receipt commit recovers without replay or relocking", () -> crash("after"));
        test("HTTP auth strict acknowledgements query evidence and readonly review", TaskReviewTests::http);
        test("shutdown retains Inbox lock while review observation is active", TaskReviewTests::shutdownReview);
        System.out.println("Task review tests: "+passed+" passed, "+failed+" failed");if(failed>0)System.exit(1);
    }

    private static void strictRequest() {
        ResolutionRequest value=vector();
        List<String> bad=List.of(value.json()+"x",value.json().replace("\"acknowledgeNoReplay\":true","\"acknowledgeNoReplay\":\"true\""),
                value.json().replace("\"acknowledgeResidualRisk\":true","\"acknowledgeResidualRisk\":false"),
                value.json().replace("\"reason\":","\"reason\":null,\"reason\":"),value.json().replace("\"reason\"","\"unknown\""),
                value.json().replace("\"instanceId\":\"sample\"","\"instanceId\":{}"),value.json().replace("\"instanceId\":\"sample\"","\"instanceId\":42"),
                value.json().replace("已核对日志","\\u0000"),value.json().replace("已核对日志","\\ud800"));
        for(String json:bad) {try{ResolutionRequest.parse(value.commandId(),json);throw new AssertionError("invalid request accepted");}catch(IllegalArgumentException expected){}}
        try{copy(value,value.resolutionId(),"x".repeat(501),value.expectedStoreId(),value.expectedNodeId(),value.expectedExecutionMode());throw new AssertionError("long reason accepted");}catch(IllegalArgumentException expected){}
    }
    private static void reasonWhitespace() {
        ResolutionRequest value=vector();
        String blanks="\t\n\u000b\f\r \u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff";
        for(char blank:blanks.toCharArray()) for(String reason:List.of(blank+"原因", "原因"+blank, String.valueOf(blank))) {
            try{copy(value,value.resolutionId(),reason,value.expectedStoreId(),value.expectedNodeId(),value.expectedExecutionMode());throw new AssertionError("noncanonical ECMA whitespace accepted");}catch(IllegalArgumentException expected){}
        }
        for(String reason:List.of("\u001c", "\u001c核对 😀\u001c", "核对\u00a0\ufeff\u3000😀", "😀".repeat(500))) {
            ResolutionRequest accepted=copy(value,value.resolutionId(),reason,value.expectedStoreId(),value.expectedNodeId(),value.expectedExecutionMode());
            equal(reason,accepted.reason());equal(accepted,ResolutionRequest.parse(accepted.commandId(),accepted.json()));
        }
        try{copy(value,value.resolutionId(),"核对\r\n日志",value.expectedStoreId(),value.expectedNodeId(),value.expectedExecutionMode());throw new AssertionError("unnormalized CR accepted");}catch(IllegalArgumentException expected){}
    }
    private static void duplicatePermission()throws Exception{
        Path dir=folder("duplicate-permission");Seed seed=seed(dir,"UNKNOWN",false);ResolutionRequest request=request(seed.command);ResolutionReceipt receipt;
        TaskService first=new TaskService(config(dir,false,true,100,Set.of("sample")),new Counter());
        try{receipt=first.resolve(request).receipt();}finally{first.close();}
        rejected(403,"review_disabled",()->first.resolve(request));equal(receipt,first.resolution(request.commandId(),request.resolutionId()));
        Counter actions=new Counter();
        try(TaskService changed=new TaskService(config(dir,false,true,100,Set.of("other")),actions)){
            rejected(403,"review_instance_not_allowed",()->changed.resolve(request));equal(receipt,changed.resolution(request.commandId(),request.resolutionId()));equal(0,actions.observations.get());
        }
        Seed another=seed(dir,"UNKNOWN",false);
        try(TaskService unhealthy=new TaskService(config(dir,false,true,100,Set.of("sample")),new Counter())){
            Files.createDirectory(dir.resolve(another.command.commandId()+".resolution"));
            rejected(503,"inbox_unavailable",()->unhealthy.resolve(request(another.command)));
            rejected(503,"inbox_unavailable",()->unhealthy.resolve(request));equal(receipt,unhealthy.resolution(request.commandId(),request.resolutionId()));
        }
    }
    private static void receiptTime(boolean future)throws Exception{
        Path dir=folder("receipt-time");Seed seed=seed(dir,"UNKNOWN",false);ResolutionRequest request=request(seed.command);Instant now=Instant.now();
        TaskView original=seed.task;
        if(future)original=new TaskView(original.taskId(),original.instanceId(),original.action(),original.status(),original.message(),original.createdAt(),original.startedAt(),now.plusSeconds(30).toString(),original.executionMode(),original.nodeId(),original.storeId(),original.resultCode(),original.observedStatus());
        String observed=future?now.toString():Instant.parse(original.finishedAt()).minusSeconds(1).toString();
        try{new ResolutionReceipt(request,original,now.toString(),"ACKNOWLEDGED_UNKNOWN","PRIOR_PROCESS_UNVERIFIED","UNAVAILABLE",null,observed);throw new AssertionError("impossible receipt chronology accepted");}
        catch(IllegalArgumentException expected){}
    }
    static ResolutionRequest vector(){return new ResolutionRequest("11111111-1111-4111-8111-111111111111","22222222-2222-4222-8222-222222222222","sample","restart","node-test","33333333-3333-4333-8333-333333333333","MOCK","2026-10-01T00:00:00Z","ACKNOWLEDGE_UNCERTAINTY","已核对日志\n未重放原命令 😀","a".repeat(64),true,true);}
    static ResolutionRequest request(TaskCommand command){return new ResolutionRequest(command.commandId(),UUID.randomUUID().toString(),command.instanceId(),command.action(),command.expectedNodeId(),command.expectedStoreId(),command.expectedExecutionMode(),command.expiresAt(),"ACKNOWLEDGE_UNCERTAINTY","已人工检查，保留不确定性","a".repeat(64),true,true);}
    private static ResolutionRequest copy(ResolutionRequest request,String id,String reason,String store,String node,String mode){return new ResolutionRequest(request.commandId(),id,request.instanceId(),request.action(),node,store,mode,request.expectedCommandExpiresAt(),request.decision(),reason,request.evidenceDigest(),true,true);}
    static AgentConfig config(Path directory,boolean control,boolean review,int capacity,Set<String> reviewInstances){return new AgentConfig(0,"node-test","test",TaskInboxTests.READ,true,"never-call-real-docker","sample","sample",2,2,8192,4,control,false,"localhost",1048576,16,TaskInboxTests.CONTROL,Set.of("sample"),directory,capacity,true,"127.0.0.1",review,reviewInstances);}
    record Seed(TaskCommand command,TaskView task){}
    static Seed seed(Path directory,String status,boolean expired)throws Exception{
        try(TaskInbox inbox=new TaskInbox(directory,100)){
            TaskCommand command=new TaskCommand(UUID.randomUUID().toString(),"sample","restart","node-test",inbox.storeId(),"MOCK",expired?Instant.now().minusSeconds(60).toString():Instant.now().plusSeconds(60).toString());
            String created=Instant.now().minusSeconds(10).toString(),started=status.equals("PENDING")?null:Instant.now().minusSeconds(9).toString(),finished=Set.of("UNKNOWN","SUCCEEDED","FAILED").contains(status)?Instant.now().minusSeconds(8).toString():null;
            String code=status.equals("UNKNOWN")?"EXECUTION_UNCERTAIN":status.equals("SUCCEEDED")?"STATE_CONFIRMED":status.equals("FAILED")?"EXPIRED":"ACCEPTED";
            TaskView task=new TaskView(command.commandId(),"sample","restart",status,code,created,started,finished,"MOCK","node-test",inbox.storeId(),code,status.equals("SUCCEEDED")?"RUNNING":null);
            inbox.put(new TaskInbox.Entry(command,"sample",task));return new Seed(command,task);
        }
    }
    static class Counter implements TaskService.ControlExecutor {
        final AtomicInteger calls=new AtomicInteger(),observations=new AtomicInteger();final ExecutionRegistry registry=new ExecutionRegistry();String target="sample";
        public String resolveTarget(String id){return target;}
        public void execute(String id,String action,Runnable before){before.run();calls.incrementAndGet();throw new IllegalStateException("uncertain");}
        public String observe(String id){observations.incrementAndGet();return "RUNNING";}
        public ExecutionRegistry executionRegistry(){return registry;}
    }
    private static void activeWorker()throws Exception{
        Path dir=folder("worker");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Counter actions=new Counter(){@Override public void execute(String id,String action,Runnable before){before.run();entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}throw new IllegalStateException("uncertain");}};
        try(TaskService service=new TaskService(config(dir,true,true,100,Set.of("sample")),actions)){
            TaskCommand command=TaskInboxTests.request(service,"sample","restart");service.submit(command);service.start();require(entered.await(5,TimeUnit.SECONDS),"worker did not start");
            try{rejected(409,"review_executor_active",()->service.resolve(request(command)));require(service.reviewEvidence(command.commandId()).workerActive(),"worker not reported");}
            finally{release.countDown();}
            TaskInboxTests.await(service,command.commandId(),"UNKNOWN");idle(service,command.commandId());equal("CURRENT_PROCESS_CLEARED",service.resolve(request(command)).receipt().processAssessment());
        }
    }
    private static void liveResource(boolean readerCase)throws Exception{
        Path dir=folder("live-resource");AtomicReference<Process> process=new AtomicReference<>();CountDownLatch stopReader=new CountDownLatch(1);AtomicReference<Thread> readerRef=new AtomicReference<>();
        Counter actions=new Counter(){@Override public void execute(String id,String action,Runnable before){before.run();try{
            Process helper=child(readerCase?new String[]{"fail"}:new String[]{"sleep",dir.resolve("pid").toString()});process.set(helper);
            Thread reader=new Thread(()->{try{stopReader.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});readerRef.set(reader);if(readerCase)reader.start();registry.register(helper,reader);
            if(readerCase)require(helper.waitFor(5,TimeUnit.SECONDS),"helper did not exit");
        }catch(Exception e){throw new IllegalStateException(e);}throw new IllegalStateException("uncertain");}};
        TaskService service=new TaskService(config(dir,true,true,100,Set.of("sample")),actions);
        try{
            TaskCommand command=TaskInboxTests.request(service,"sample","restart");service.submit(command);service.start();TaskInboxTests.await(service,command.commandId(),"UNKNOWN");idle(service,command.commandId());
            require(service.reviewEvidence(command.commandId()).knownProcessesActive(),"active owned resource forgotten");
            rejected(409,"review_executor_active",()->service.resolve(request(command)));
            if(process.get().isAlive()){process.get().destroyForcibly();require(process.get().waitFor(5,TimeUnit.SECONDS),"helper did not exit");}
            stopReader.countDown();readerRef.get().join(5000);service.resolve(request(command));
        }finally{stopReader.countDown();if(readerRef.get()!=null)readerRef.get().join(5000);if(process.get()!=null&&process.get().isAlive()){process.get().destroyForcibly();process.get().waitFor(5,TimeUnit.SECONDS);}service.close();}
    }
    private static void observationResource()throws Exception{
        Path dir=folder("observation-resource");Seed seed=seed(dir,"UNKNOWN",false);AtomicReference<Process> process=new AtomicReference<>();AtomicBoolean once=new AtomicBoolean();
        Counter actions=new Counter(){@Override public String observe(String id){if(once.compareAndSet(false,true))try{Process helper=child("sleep",dir.resolve("pid").toString());process.set(helper);registry.register(helper,new Thread());}catch(Exception e){throw new IllegalStateException(e);}return "RUNNING";}};
        TaskService service=new TaskService(config(dir,false,true,100,Set.of("sample")),actions);
        try{ResolutionRequest request=request(seed.command);rejected(409,"review_executor_active",()->service.resolve(request));equal(null,service.resolution(request.commandId(),request.resolutionId()));equal("OWNED_BY_COMMAND",service.reviewEvidence(request.commandId()).lockDisposition());
            process.get().destroyForcibly();require(process.get().waitFor(5,TimeUnit.SECONDS),"observation helper did not exit");service.resolve(request);
        }finally{if(process.get()!=null&&process.get().isAlive()){process.get().destroyForcibly();process.get().waitFor(5,TimeUnit.SECONDS);}service.close();}
    }
    private static void writeFailure()throws Exception{
        Path dir=folder("write-fail");Seed seed=seed(dir,"UNKNOWN",false);byte[] original=Files.readAllBytes(dir.resolve(seed.command.commandId()+".task"));
        try(TaskService service=new TaskService(config(dir,true,true,100,Set.of("sample")),new Counter())){
            Files.createDirectory(dir.resolve(seed.command.commandId()+".resolution"));
            rejected(503,"inbox_unavailable",()->service.resolve(request(seed.command)));require(!service.reviewEnabled()&&!service.controlEnabled(),"failed disk still enabled writes");
            equal("OWNED_BY_COMMAND",service.reviewEvidence(seed.command.commandId()).lockDisposition());require(Arrays.equals(original,Files.readAllBytes(dir.resolve(seed.command.commandId()+".task"))),"failed receipt changed task");
        }
    }
    private static void corrupt(String kind)throws Exception{
        Path dir=folder("corrupt-"+kind);Seed seed=seed(dir,"UNKNOWN",false);
        try(TaskService service=new TaskService(config(dir,false,true,100,Set.of("sample")),new Counter())){service.resolve(request(seed.command));}
        Path record=dir.resolve(seed.command.commandId()+".resolution");
        if(kind.equals("checksum")){byte[] bytes=Files.readAllBytes(record);bytes[12]^=1;Files.write(record,bytes);}
        else if(kind.equals("hash")){byte[] bytes=Files.readAllBytes(record);int length=ByteBuffer.wrap(bytes,4,4).getInt();String payload=new String(bytes,8,length,StandardCharsets.UTF_8);String hash=Json.flatObject(payload).get("requestHash");payload=payload.replace(hash,"0".repeat(64));Files.write(record,pack(payload));}
        else if(kind.equals("orphan"))Files.move(dir.resolve(seed.command.commandId()+".task"),dir.resolve("original.saved"));
        else Files.move(dir.resolve("store.id"),dir.resolve("store.saved"));
        rejected(503,"inbox_unavailable",()->new TaskService(config(dir,false,true,100,Set.of("sample")),new Counter()));
    }
    private static void sameResolutionId()throws Exception{
        Path dir=folder("same-id");Seed first=seed(dir,"SUCCEEDED",false),second=seed(dir,"FAILED",false);
        try(TaskService service=new TaskService(config(dir,false,true,100,Set.of("sample")),new Counter())){
            ResolutionRequest a=request(first.command),b=request(second.command);service.resolve(a);
            rejected(409,"resolution_conflict",()->service.resolve(copy(b,a.resolutionId(),b.reason(),b.expectedStoreId(),b.expectedNodeId(),b.expectedExecutionMode())));
        }
    }
    private static void crash(String phase)throws Exception{
        Path dir=folder("crash-"+phase);Seed seed=seed(dir,"UNKNOWN",false);ResolutionRequest request=request(seed.command);Files.writeString(dir.resolve("review-request.json"),request.json());Files.writeString(dir.resolve("command-id.txt"),seed.command.commandId());
        byte[] original=Files.readAllBytes(dir.resolve(seed.command.commandId()+".task"));
        List<String> command=javaCommand(TaskReviewCrashChild.class.getName(),phase,dir.toString());Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
        try{require(process.waitFor(10,TimeUnit.SECONDS),"crash child stuck");equal(49,process.exitValue());}
        finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
        Counter actions=new Counter();
        try(TaskService service=new TaskService(config(dir,true,true,100,Set.of("sample")),actions)){
            equal(seed.task,service.get(seed.command.commandId()));equal(seed.task,service.submit(seed.command).task());equal(0,actions.calls.get());
            if(phase.equals("before")){equal(null,service.resolution(request.commandId(),request.resolutionId()));rejected(409,"instance_busy",()->service.submit(TaskInboxTests.request(service,"sample","start")));}
            else{require(service.resolution(request.commandId(),request.resolutionId())!=null,"committed receipt lost");service.submit(TaskInboxTests.request(service,"sample","start"));service.resolve(request);rejected(409,"instance_busy",()->service.submit(TaskInboxTests.request(service,"sample","stop")));}
        }
        require(Arrays.equals(original,Files.readAllBytes(dir.resolve(seed.command.commandId()+".task"))),"recovery rewrote original record");
    }
    private static void http()throws Exception{
        Path dir=folder("http");Seed seed=seed(dir,"UNKNOWN",false);ResolutionRequest request=request(seed.command);
        try(AgentServer server=new AgentServer(config(dir,false,true,100,Set.of("sample")))){
            server.start();String base="/api/agent/tasks/"+seed.command.commandId();
            equal(401,call(server,base+"/review-evidence",TaskInboxTests.READ,null).statusCode());equal(401,call(server,base+"/resolutions",TaskInboxTests.READ,request.json()).statusCode());
            equal(200,call(server,base+"/review-evidence",TaskInboxTests.CONTROL,null).statusCode());
            equal(400,call(server,base+"/resolutions",TaskInboxTests.CONTROL,request.json().replace("\"acknowledgeNoReplay\":true","\"acknowledgeNoReplay\":\"true\"")).statusCode());
            equal(201,call(server,base+"/resolutions",TaskInboxTests.CONTROL,request.json()).statusCode());equal(200,call(server,base+"/resolutions",TaskInboxTests.CONTROL,request.json()).statusCode());
            equal(200,call(server,base+"/resolutions/"+request.resolutionId(),TaskInboxTests.CONTROL,null).statusCode());equal(401,call(server,base+"/resolutions/"+request.resolutionId(),TaskInboxTests.READ,null).statusCode());
            equal(404,call(server,base+"/resolutions/"+UUID.randomUUID(),TaskInboxTests.CONTROL,null).statusCode());
            String health=call(server,"/api/agent/health",TaskInboxTests.READ,null).body();require(health.contains("\"controlEnabled\":false")&&health.contains("\"reviewEnabled\":true")&&health.contains("\"reviewProtocolVersion\":\"1.0\""),"independent health flags missing");
        }
        try(AgentServer server=new AgentServer(config(dir,false,false,100,Set.of()))){server.start();String base="/api/agent/tasks/"+seed.command.commandId()+"/resolutions";equal(403,call(server,base,TaskInboxTests.CONTROL,request.json()).statusCode());equal(200,call(server,base+"/"+request.resolutionId(),TaskInboxTests.CONTROL,null).statusCode());}
    }
    private static HttpResponse<String> call(AgentServer server,String path,String token,String body)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.listeningPort()+path)).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+token);
        if(body!=null)builder.POST(HttpRequest.BodyPublishers.ofString(body));return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static void shutdownReview()throws Exception{
        Path dir=folder("shutdown-review");Seed seed=seed(dir,"UNKNOWN",false);CountDownLatch observing=new CountDownLatch(1),release=new CountDownLatch(1);
        Counter actions=new Counter(){@Override public String observe(String id){observing.countDown();boolean done=false;while(!done){try{release.await();done=true;}catch(InterruptedException ignored){}}return "RUNNING";}};
        TaskService service=new TaskService(config(dir,false,true,100,Set.of("sample")),actions);ExecutorService executor=Executors.newFixedThreadPool(2);
        try{
            Future<?> review=executor.submit(()->service.resolve(request(seed.command)));require(observing.await(5,TimeUnit.SECONDS),"review did not enter observation");
            Future<?> closing=executor.submit(service::close);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(service.reviewEnabled()&&System.nanoTime()<deadline)Thread.sleep(10);
            require(!service.reviewEnabled()&&!closing.isDone(),"close skipped active review scope");
            rejected(503,"inbox_unavailable",()->new TaskInbox(dir,100));
            release.countDown();
            try{review.get(5,TimeUnit.SECONDS);throw new AssertionError("review persisted after close");}catch(ExecutionException expected){require(expected.getCause() instanceof TaskRejected rejected&&rejected.getMessage().equals("review_disabled"),"unexpected review close outcome");}
            closing.get(5,TimeUnit.SECONDS);try(TaskInbox reopened=new TaskInbox(dir,100)){equal(null,reopened.resolution(seed.command.commandId()));}
        }finally{release.countDown();executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS);service.close();}
    }
    static byte[] pack(String value)throws Exception{byte[] payload=value.getBytes(StandardCharsets.UTF_8);byte[] digest=MessageDigest.getInstance("SHA-256").digest(payload);return ByteBuffer.allocate(8+payload.length+32).putInt(0x41525231).putInt(payload.length).put(payload).put(digest).array();}
    private static void idle(TaskService service,String id)throws Exception{for(int i=0;i<100;i++){if(!service.reviewEvidence(id).workerActive())return;Thread.sleep(10);}throw new AssertionError("worker stayed active");}
    private static List<String> javaCommand(String main,String...args){List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Djava.io.tmpdir="+root,"-cp",System.getProperty("java.class.path"),main));command.addAll(List.of(args));return command;}
    private static Process child(String...args)throws Exception{return new ProcessBuilder(javaCommand(AgentTestChild.class.getName(),args)).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();}
    private static Path folder(String name)throws Exception{Path dir=root.resolve(name+"-"+UUID.randomUUID());Files.createDirectories(dir);return dir;}
    private interface Checked{void run()throws Exception;}
    private static void test(String name,Checked action){try{action.run();passed++;System.out.println("[PASS] "+name);}catch(Throwable failure){failed++;System.err.println("[FAIL] "+name+" - "+failure);failure.printStackTrace();}}
    private static void rejected(int status,String code,Checked action)throws Exception{try{action.run();throw new AssertionError("expected rejection "+code);}catch(TaskRejected actual){equal(status,actual.status());equal(code,actual.getMessage());}}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("expected "+expected+" but was "+actual);}
}
