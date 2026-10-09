package com.argus.controlcenter.controller;

import com.argus.controlcenter.service.AiDiagnosticService;
import com.argus.controlcenter.vo.AiReply;
import com.argus.controlcenter.vo.ApiResponse;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final AiDiagnosticService service;
    public AiController(AiDiagnosticService service) { this.service = service; }
    @GetMapping("/diagnose")
    public ApiResponse<AiReply> diagnose(@RequestParam @Size(max = 4000) String question) { return ApiResponse.ok(service.ask(question)); }
}
