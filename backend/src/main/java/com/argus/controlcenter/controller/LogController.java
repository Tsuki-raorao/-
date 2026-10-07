package com.argus.controlcenter.controller;

import com.argus.controlcenter.domain.LogEntry;
import com.argus.controlcenter.service.LogService;
import com.argus.controlcenter.vo.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 实例日志只读查询接口。 */
@RestController
@RequestMapping("/api/logs")
public class LogController {
    private final LogService service;

    public LogController(LogService service) {
        this.service = service;
    }

    /** 按实例筛选日志，并限制返回数量防止一次读取过大。 */
    @GetMapping
    public ApiResponse<List<LogEntry>> list(@RequestParam(required = false) String instanceId,
                                            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(service.find(instanceId, limit));
    }
}
