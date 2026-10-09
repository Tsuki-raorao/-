package com.argus.controlcenter.controller;

import com.argus.controlcenter.config.ApiAccessInterceptor;
import com.argus.controlcenter.domain.TaskResolution;
import com.argus.controlcenter.service.TaskReviewService;
import com.argus.controlcenter.vo.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.argus.controlcenter.identity.CurrentActor;
import com.argus.controlcenter.identity.CurrentActorProvider;
import com.argus.controlcenter.identity.IdentityAuthorizationException;
import com.argus.controlcenter.identity.IdentityConstants;
import com.argus.controlcenter.identity.ProjectAuthorization;
import com.argus.controlcenter.identity.ProjectPermission;

/** 独立人工核对入口，不接受任何执行动作或客户端actor。 */
@RestController
public class TaskReviewController {
    private final TaskReviewService service;
    private final CurrentActorProvider actorProvider;
    private final ProjectAuthorization authorization;
    private final com.argus.controlcenter.service.TaskReviewAuthorizationService authorizations;
    public TaskReviewController(TaskReviewService service,CurrentActorProvider actorProvider,ProjectAuthorization authorization,com.argus.controlcenter.service.TaskReviewAuthorizationService authorizations){this.service=service;this.actorProvider=actorProvider;this.authorization=authorization;this.authorizations=authorizations;}
    @GetMapping("/api/tasks/{id}/review-capabilities")
    public ApiResponse<TaskReviewService.Capabilities> capabilities(@PathVariable String id,@RequestParam(required=false)String projectId,HttpServletRequest request){CurrentActor actor=actorProvider.current();String scope=scope(projectId,actor);return ApiResponse.ok(service.capabilities(id,operator(request),scope,actor));}
    @GetMapping("/api/tasks/{id}/review-evidence")
    public ApiResponse<JsonNode> evidence(@PathVariable String id,@RequestParam(required=false)String projectId){CurrentActor actor=actorProvider.current();String scope=scope(projectId,actor);return ApiResponse.ok(service.evidence(id,scope,actor));}
    @PostMapping("/api/tasks/{id}/resolutions")
    public ResponseEntity<ApiResponse<TaskResolution>> submit(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestParam(required=false)String projectId,@RequestBody String body,HttpServletRequest request){CurrentActor actor=actorProvider.current();return ResponseEntity.accepted().body(ApiResponse.ok(service.submit(id,key,body,operator(request),projectId,actor)));}
    @GetMapping("/api/tasks/{id}/resolution")
    public ApiResponse<TaskResolution> byTask(@PathVariable String id,@RequestParam(required=false)String projectId){CurrentActor actor=actorProvider.current();String scope=scope(projectId,actor);return ApiResponse.ok(service.byTaskScoped(id,scope,actor));}
    @GetMapping("/api/task-resolutions/{id}")
    public ApiResponse<TaskResolution> find(@PathVariable String id,@RequestParam(required=false)String projectId){CurrentActor actor=actorProvider.current();String scope=scope(projectId,actor);return ApiResponse.ok(service.findScoped(id,scope,actor));}
    @GetMapping("/api/task-resolutions/{id}/events")
    public ApiResponse<List<TaskResolution.Event>> events(@PathVariable String id,@RequestParam(required=false)String projectId){CurrentActor actor=actorProvider.current();String scope=scope(projectId,actor);service.findScoped(id,scope,actor);return ApiResponse.ok(service.events(id));}
    private boolean operator(HttpServletRequest request){return Boolean.TRUE.equals(request.getAttribute(ApiAccessInterceptor.OPERATOR_ATTRIBUTE));}
    private String scope(String projectId,CurrentActor actor){if(projectId==null||projectId.isBlank()){if(authorization.isIdentityMode())throw new IdentityAuthorizationException("PROJECT_REQUIRED",400);return IdentityConstants.LEGACY_PROJECT_ID;}authorization.require(actor,projectId,ProjectPermission.RESOURCE_READ);return projectId;}
    @GetMapping("/api/task-resolutions/by-request-key/{key}")
    public ApiResponse<TaskResolution> byRequestKey(@PathVariable String key,@RequestParam(required=false)String projectId){CurrentActor actor=actorProvider.current();String scope=scope(projectId,actor);return ApiResponse.ok(service.byRequestKeyScoped(key,scope,actor));}
    @PostMapping("/api/task-resolutions/{id}/authorizations")
    public ResponseEntity<ApiResponse<com.argus.controlcenter.service.TaskReviewAuthorizationService.Grant>> adopt(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestParam(required=false)String projectId,@RequestBody com.argus.controlcenter.service.TaskReviewAuthorizationService.AdoptionRequest body){CurrentActor actor=actorProvider.current();String scope=scope(projectId,actor);return ResponseEntity.accepted().body(ApiResponse.ok(authorizations.adopt(id,key,scope,body,actor)));}
    @GetMapping("/api/task-resolutions/{id}/authorizations/by-request-key/{key}")
    public ApiResponse<com.argus.controlcenter.service.TaskReviewAuthorizationService.Grant> adoptionByRequestKey(@PathVariable String id,@PathVariable String key,@RequestParam(required=false)String projectId){CurrentActor actor=actorProvider.current();String scope=scope(projectId,actor);return ApiResponse.ok(authorizations.find(id,key,scope,actor));}
}
