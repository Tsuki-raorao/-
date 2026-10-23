package com.argus.controlcenter.controller;

import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.dto.*;
import com.argus.controlcenter.identity.*;
import com.argus.controlcenter.service.AiAgentService;
import com.argus.controlcenter.vo.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/agent")
public class AiAgentController {
    private final AiAgentService service; private final CurrentActorProvider actors;
    public AiAgentController(AiAgentService service,CurrentActorProvider actors){this.service=service;this.actors=actors;}
    @PostMapping("/sessions") public ResponseEntity<ApiResponse<AiAgentSession>> create(@RequestParam(required=false) String projectId,@Valid @RequestBody CreateAiSessionRequest body){return ResponseEntity.ok(ApiResponse.created(service.createSession(projectId,body.getTitle(),actors.current())));}
    @PostMapping("/sessions/{sessionId}/runs") public ResponseEntity<ApiResponse<AiAgentRun>> run(@PathVariable String sessionId,@RequestParam(required=false) String projectId,@Valid @RequestBody AiRunRequest body){return ResponseEntity.ok(ApiResponse.ok(service.run(sessionId,projectId,body.getQuestion(),actors.current())));}
    @GetMapping("/runs/{id}") public ApiResponse<AiAgentRun> get(@PathVariable String id,@RequestParam(required=false) String projectId){return ApiResponse.ok(service.get(id,projectId,actors.current()));}
    @GetMapping("/runs/{id}/steps") public ApiResponse<?> steps(@PathVariable String id,@RequestParam(required=false) String projectId){return ApiResponse.ok(service.steps(id,projectId,actors.current()));}
}
