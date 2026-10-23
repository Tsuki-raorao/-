package com.argus.controlcenter.controller;
import com.argus.controlcenter.dto.CreateAiKnowledgeRequest;
import com.argus.controlcenter.identity.*;
import com.argus.controlcenter.service.AiKnowledgeService;
import com.argus.controlcenter.vo.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/ai/knowledge") public class AiKnowledgeController {
 private final AiKnowledgeService service; private final CurrentActorProvider actors;
 public AiKnowledgeController(AiKnowledgeService service,CurrentActorProvider actors){this.service=service;this.actors=actors;}
 @PostMapping public ApiResponse<String> create(@RequestParam(required=false) String projectId,@Valid @RequestBody CreateAiKnowledgeRequest body){return ApiResponse.created(service.create(projectId,body,actors.current()));}
 @GetMapping("/search") public ApiResponse<?> search(@RequestParam(required=false) String projectId,@RequestParam String q){return ApiResponse.ok(service.search(projectId,q,actors.current()));}
}
