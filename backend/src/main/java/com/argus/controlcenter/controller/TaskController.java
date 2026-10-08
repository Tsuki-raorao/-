package com.argus.controlcenter.controller;

import com.argus.controlcenter.domain.Task;
import com.argus.controlcenter.service.TaskService;
import com.argus.controlcenter.vo.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 任务及追加事件查询；控制关闭后仍可读取已保存结果。 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    /** 返回最近任务及其执行状态。 */
    @GetMapping
    public ApiResponse<List<Task>> list() {
        return ApiResponse.ok(service.findAll());
    }

    /** 按任务 ID 查询执行详情。 */
    @GetMapping("/{id}")
    public ApiResponse<Task> get(@PathVariable String id) {
        return ApiResponse.ok(service.findById(id));
    }
    @GetMapping("/{id}/events")
    public ApiResponse<List<com.argus.controlcenter.domain.TaskEvent>> events(@PathVariable String id) {
        return ApiResponse.ok(service.events(id));
    }
}
