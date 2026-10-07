package com.argus.controlcenter.controller;

import com.argus.controlcenter.domain.Instance;
import com.argus.controlcenter.dto.ActionRequest;
import com.argus.controlcenter.service.InstanceService;
import com.argus.controlcenter.service.TaskService;
import com.argus.controlcenter.vo.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 服务实例查询和动作入口；生产环境动作接口当前保持只读保护。 */
@RestController
@RequestMapping("/api/instances")
public class InstanceController {
    private final InstanceService instances;
    private final TaskService tasks;

    public InstanceController(InstanceService instances, TaskService tasks) {
        this.instances = instances;
        this.tasks = tasks;
    }

    /** 返回所有节点同步到控制中心的实例快照。 */
    @GetMapping
    public ApiResponse<List<Instance>> list() {
        return ApiResponse.ok(instances.findAll());
    }

    /** 按实例 ID 查询状态和资源指标。 */
    @GetMapping("/{id}")
    public ApiResponse<Instance> get(@PathVariable String id) {
        return ApiResponse.ok(instances.findById(id));
    }

    /** 创建一个动作任务；是否允许执行由生产只读拦截器决定。 */
    @PostMapping("/{id}/actions")
    public ApiResponse<com.argus.controlcenter.domain.Task> action(@PathVariable String id,
                                                                    @Valid @RequestBody ActionRequest request) {
        return ApiResponse.created(tasks.execute(id, request.getAction()));
    }
}
