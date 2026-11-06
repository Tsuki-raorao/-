package com.argus.controlcenter.controller;

import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.dto.*;
import com.argus.controlcenter.config.ApiAccessInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import com.argus.controlcenter.identity.*;
import com.argus.controlcenter.service.AiAgentService;
import com.argus.controlcenter.service.AiRunEvaluationService;
import com.argus.controlcenter.vo.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/agent")
public class AiAgentController {
    private final AiAgentService service; private final AiRunEvaluationService evaluation; private final CurrentActorProvider actors;
    public AiAgentController(AiAgentService service,AiRunEvaluationService evaluation,CurrentActorProvider actors){this.service=service;this.evaluation=evaluation;this.actors=actors;}
    @PostMapping("/sessions") public ResponseEntity<ApiResponse<AiAgentSession>> create(@RequestParam(required=false) String projectId,@Valid @RequestBody CreateAiSessionRequest body){return ResponseEntity.ok(ApiResponse.created(service.createSession(projectId,body.getTitle(),actors.current())));}
    @PostMapping("/sessions/{sessionId}/runs") public ResponseEntity<ApiResponse<AiAgentRun>> run(@PathVariable String sessionId,@RequestParam(required=false) String projectId,@Valid @RequestBody AiRunRequest body){return ResponseEntity.ok(ApiResponse.ok(service.run(sessionId,projectId,body.getQuestion(),actors.current())));}
    @GetMapping("/runs/{id}") public ApiResponse<AiAgentRun> get(@PathVariable String id,@RequestParam(required=false) String projectId){return ApiResponse.ok(service.get(id,projectId,actors.current()));}
    @GetMapping("/runs/{id}/steps") public ApiResponse<?> steps(@PathVariable String id,@RequestParam(required=false) String projectId){return ApiResponse.ok(service.steps(id,projectId,actors.current()));}
    @GetMapping("/runs/{id}/evaluation") public ApiResponse<?> evaluation(@PathVariable String id,@RequestParam(required=false) String projectId){return ApiResponse.ok(evaluation.evaluate(id,projectId,actors.current()));}
    @GetMapping("/runs/evaluation/batch") public ApiResponse<?> batchEvaluation(@RequestParam(required=false) String projectId,@RequestParam(defaultValue="60") int windowMinutes,@RequestParam(defaultValue="100") int limit){return ApiResponse.ok(evaluation.evaluateBatch(projectId,windowMinutes,limit,actors.current()));}
    @PostMapping("/runs/{id}/retry") public ResponseEntity<ApiResponse<AiAgentRun>> retry(@PathVariable String id,@RequestParam(required=false) String projectId,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody(required=false) RetryAiRunRequest body){String reason=body==null?null:body.getReason();return ResponseEntity.accepted().body(ApiResponse.ok(service.retry(id,projectId,key,reason,actors.current())));}
    @PostMapping("/runs/{id}/approve") public ResponseEntity<ApiResponse<AiAgentRun>> approve(@PathVariable String id,@RequestParam(required=false) String projectId,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ApproveAiRunRequest body,HttpServletRequest request){CurrentActor actor=actors.current();boolean legacy=Boolean.TRUE.equals(request.getAttribute(ApiAccessInterceptor.OPERATOR_ATTRIBUTE))&&actor.legacyOperator();return ResponseEntity.accepted().body(ApiResponse.ok(service.approve(id,projectId,body.getInstanceId(),body.getExpectedExecutionMode(),key,actor,legacy)));}
    @PostMapping("/runs/{id}/verify") public ApiResponse<AiAgentRun> verify(@PathVariable String id,@RequestParam(required=false) String projectId){return ApiResponse.ok(service.verify(id,projectId,actors.current()));}
}
