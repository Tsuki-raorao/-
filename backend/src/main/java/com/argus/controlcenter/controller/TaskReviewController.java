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

/** 独立人工核对入口，不接受任何执行动作或客户端actor。 */
@RestController
public class TaskReviewController {
    private final TaskReviewService service;
    public TaskReviewController(TaskReviewService service){this.service=service;}
    @GetMapping("/api/tasks/{id}/review-capabilities")
    public ApiResponse<TaskReviewService.Capabilities> capabilities(@PathVariable String id,HttpServletRequest request){return ApiResponse.ok(service.capabilities(id,operator(request)));}
    @GetMapping("/api/tasks/{id}/review-evidence")
    public ApiResponse<JsonNode> evidence(@PathVariable String id){return ApiResponse.ok(service.evidence(id));}
    @PostMapping("/api/tasks/{id}/resolutions")
    public ResponseEntity<ApiResponse<TaskResolution>> submit(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String body,HttpServletRequest request){return ResponseEntity.accepted().body(ApiResponse.ok(service.submit(id,key,body,operator(request))));}
    @GetMapping("/api/tasks/{id}/resolution")
    public ApiResponse<TaskResolution> byTask(@PathVariable String id){return ApiResponse.ok(service.byTask(id));}
    @GetMapping("/api/task-resolutions/{id}")
    public ApiResponse<TaskResolution> find(@PathVariable String id){return ApiResponse.ok(service.find(id));}
    @GetMapping("/api/task-resolutions/{id}/events")
    public ApiResponse<List<TaskResolution.Event>> events(@PathVariable String id){return ApiResponse.ok(service.events(id));}
    private boolean operator(HttpServletRequest request){return Boolean.TRUE.equals(request.getAttribute(ApiAccessInterceptor.OPERATOR_ATTRIBUTE));}
}
