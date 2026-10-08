package com.argus.controlcenter.service;

import com.argus.controlcenter.config.*;
import com.argus.controlcenter.domain.TaskCommand;
import com.argus.controlcenter.domain.TaskStatus;
import com.argus.controlcenter.exception.TaskControlException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/** 仅允许固定 health/tasks 路径；绑定地址不随节点后续编辑漂移，控制令牌不走读取代理。 */
@Service
public class AgentCommandGateway {
    public record Health(String nodeId,String storeId,String executionMode,boolean controlEnabled) { }
    public record Reply(TaskStatus status,String resultCode) { }
    public record Response(int status,JsonNode body) { }
    private final AgentGatewayProperties gateway;
    private final TaskControlProperties tasks;
    private final HttpClient client;
    private final ObjectMapper json;
    public AgentCommandGateway(AgentGatewayProperties gateway,TaskControlProperties tasks,ObjectMapper mapper) {
        this.gateway=gateway; this.tasks=tasks;
        client=HttpClient.newBuilder().connectTimeout(gateway.getConnectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        json=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    public String normalizeAddress(String address) {
        try {
            String raw=address.trim();
            if (!raw.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*$")) raw="http://"+raw;
            URI uri=URI.create(raw);
            if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                    || uri.getRawQuery()!=null || uri.getRawFragment()!=null || (uri.getPath()!=null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")))
                throw new IllegalArgumentException();
            return new URI(uri.getScheme(),null,uri.getHost().toLowerCase(Locale.ROOT),uri.getPort(),null,null,null).toString();
        } catch (Exception e) { throw new TaskControlException(400,"INVALID_AGENT_ADDRESS"); }
    }
    public Health health(String address) {
        Response response=exchange(address,"/api/agent/health",null,false);
        if(response.status()!=200) throw new TaskControlException(502,"AGENT_HEALTH_UNAVAILABLE");
        JsonNode body=response.body();
        if (!body.isObject() || !"1.0".equals(text(body,"taskProtocolVersion")) || !body.path("controlEnabled").isBoolean())
            throw new TaskControlException(502,"AGENT_TASK_PROTOCOL_INVALID");
        String mode=text(body,"executionMode");
        if (!Set.of("MOCK","DOCKER").contains(mode)) throw new TaskControlException(502,"AGENT_TASK_PROTOCOL_INVALID");
        String nodeId=identity(body,"nodeId"),storeId=identity(body,"storeId");
        return new Health(nodeId,storeId,mode,body.path("controlEnabled").booleanValue());
    }
    public Response query(TaskCommand command) {
        return exchange(command.targetAddress(),"/api/agent/tasks/"+command.task().getCommandId(),null,true);
    }
    public Response post(TaskCommand command) {
        Map<String,String> payload=new LinkedHashMap<>();
        payload.put("commandId",command.task().getCommandId());
        payload.put("instanceId",command.task().getAgentInstanceId());
        payload.put("action",command.task().getAction().toLowerCase(Locale.ROOT));
        payload.put("expectedNodeId",command.agentNodeId());
        payload.put("expectedStoreId",command.storeId());
        payload.put("expectedExecutionMode",command.task().getExecutionMode());
        payload.put("expiresAt",command.expiresAt().toString());
        try { return exchange(command.targetAddress(),"/api/agent/tasks",json.writeValueAsString(payload),true); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    public Reply validate(TaskCommand command,JsonNode body) {
        if (!body.isObject() || !command.task().getCommandId().equals(text(body,"taskId"))
                || !command.task().getAgentInstanceId().equals(text(body,"instanceId"))
                || !command.task().getAction().toLowerCase(Locale.ROOT).equals(text(body,"action"))
                || !command.agentNodeId().equals(text(body,"nodeId")) || !command.storeId().equals(text(body,"storeId"))
                || !command.task().getExecutionMode().equals(text(body,"executionMode")))
            throw new TaskControlException(502,"AGENT_TASK_IDENTITY_MISMATCH");
        String state=text(body,"status");
        if (!Set.of("PENDING","RUNNING","SUCCEEDED","FAILED","UNKNOWN").contains(state))
            throw new TaskControlException(502,"AGENT_TASK_RESPONSE_INVALID");
        try {
            Instant created=Instant.parse(text(body,"createdAt"));
            Instant started=optionalInstant(body,"startedAt"),finished=optionalInstant(body,"finishedAt");
            if (started!=null && started.isBefore(created) || finished!=null && finished.isBefore(created)
                    || started!=null && finished!=null && finished.isBefore(started)
                    || Set.of("SUCCEEDED","FAILED").contains(state) && finished==null
                    || Set.of("RUNNING","SUCCEEDED").contains(state) && started==null
                    || Set.of("PENDING","RUNNING").contains(state) && finished!=null)
                throw new IllegalArgumentException();
            if (!body.path("message").isTextual() || !(body.has("observedStatus") && (body.get("observedStatus").isNull() || body.get("observedStatus").isTextual())))
                throw new IllegalArgumentException();
            if (state.equals("SUCCEEDED") && !(command.task().getAction().equals("STOP")?"STOPPED":"RUNNING").equals(text(body,"observedStatus")))
                throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new TaskControlException(502,"AGENT_TASK_RESPONSE_INVALID"); }
        String code=text(body,"resultCode");
        if (!code.matches("[A-Za-z0-9_.-]{1,64}")) throw new TaskControlException(502,"AGENT_TASK_RESPONSE_INVALID");
        return new Reply(state.equals("PENDING")?TaskStatus.DELIVERED:TaskStatus.valueOf(state),code);
    }
    private Instant optionalInstant(JsonNode body,String field) {
        if (!body.has(field)) throw new IllegalArgumentException();
        return body.get(field).isNull()?null:Instant.parse(text(body,field));
    }
    private String identity(JsonNode body,String name) {
        String value=text(body,name);
        if (value.isBlank() || value.length()>128 || value.chars().anyMatch(Character::isISOControl))
            throw new TaskControlException(502,"AGENT_TASK_PROTOCOL_INVALID");
        return value;
    }
    private static String text(JsonNode body,String field) {
        return body.path(field).isTextual()?body.path(field).textValue():"";
    }
    private Response exchange(String address,String path,String payload,boolean control) {
        if(!gateway.isEnabled() || !gateway.isReadOnly() || !gateway.isRequireAllowlist())
            throw new TaskControlException(503,"AGENT_GATEWAY_DISABLED");
        URI uri=URI.create(normalizeAddress(address)+path);
        if(gateway.getAllowedHosts().stream().noneMatch(host->host.trim().equalsIgnoreCase(uri.getHost())))
            throw new TaskControlException(403,"AGENT_HOST_NOT_ALLOWED");
        String token=control?tasks.getAgentControlToken():gateway.getAuthToken();
        if(token.isBlank()) throw new TaskControlException(503,"AGENT_TOKEN_MISSING");
        HttpRequest.Builder builder=HttpRequest.newBuilder(uri).timeout(gateway.getReadTimeout())
                .header("Authorization","Bearer "+token).header("Accept","application/json");
        if(payload==null) builder.GET();
        else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload));
        CompletableFuture<HttpResponse<String>> pending=null;
        AtomicReference<LimitedBody> subscriber=new AtomicReference<>();
        try {
            pending=client.sendAsync(builder.build(),info -> {
                LimitedBody body=new LimitedBody();subscriber.set(body);return body;
            });
            // JDK 17 HttpRequest.timeout 不能替代正文截止；完整 future 必须有独立总预算。
            HttpResponse<String> response=pending.get(gateway.getConnectTimeout().plus(gateway.getReadTimeout()).toMillis(),TimeUnit.MILLISECONDS);
            // 非成功正文一律不透传、不记录；明确 404 是唯一可进入重投判断的缺失证据。
            JsonNode body;
            try {
                body=response.statusCode()>=200 && response.statusCode()<300 ? json.readTree(response.body()):json.nullNode();
                if(body==null) throw new IllegalArgumentException();
            } catch(Exception invalid) {
                throw new TaskControlException(502,control?"AGENT_TASK_RESPONSE_INVALID":"AGENT_TASK_PROTOCOL_INVALID");
            }
            return new Response(response.statusCode(),body);
        } catch(TaskControlException e) {
            throw e;
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskControlException(502,"AGENT_REQUEST_INTERRUPTED");
        } catch(Exception e) {
            throw new TaskControlException(502,"AGENT_REQUEST_UNCONFIRMED");
        } finally {
            if(pending!=null && !pending.isDone()) pending.cancel(true);
            LimitedBody body=subscriber.get();if(body!=null)body.cancel();
        }
    }
    /** 有界响应订阅器；HTTP 超时覆盖完整响应，避免流式读取绕开请求截止时间。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private volatile Flow.Subscription subscription;
        public CompletionStage<String> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;if(result.isDone())value.cancel();else value.request(1);}
        public void onNext(List<ByteBuffer> buffers) {
            if(result.isDone()) return;
            for(ByteBuffer buffer:buffers) {
                if(bytes.size()+buffer.remaining()>65536) {
                    subscription.cancel();result.completeExceptionally(new IllegalArgumentException("response too large"));return;
                }
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toString(StandardCharsets.UTF_8));}
        void cancel() { Flow.Subscription current=subscription;if(current!=null)current.cancel();result.cancel(true); }
    }
}
