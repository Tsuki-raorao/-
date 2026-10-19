package com.argus.controlcenter.controller;

import com.argus.controlcenter.config.MonitoringProperties;
import com.argus.controlcenter.identity.*;
import com.argus.controlcenter.service.MonitoringStore;
import com.argus.controlcenter.vo.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/monitoring")
public class MonitoringController {
    private final MonitoringStore store;private final MonitoringProperties properties;private final CurrentActorProvider actors;private final ProjectAuthorization authorization;
    public MonitoringController(MonitoringStore store,MonitoringProperties properties,CurrentActorProvider actors,ProjectAuthorization authorization){this.store=store;this.properties=properties;this.actors=actors;this.authorization=authorization;}
    private String scope(String project){
        if(project==null||project.isBlank()){if(authorization.isIdentityMode())throw new IdentityAuthorizationException("PROJECT_REQUIRED",400);project=IdentityConstants.LEGACY_PROJECT_ID;}
        authorization.require(actors.current(),project,ProjectPermission.RESOURCE_READ);return project;
    }
    @GetMapping("/summary") public ApiResponse<?> summary(@RequestParam(required=false) String projectId){
        String project=scope(projectId);return ApiResponse.ok(Map.of("enabled",properties.isEnabled(),"alerts",store.alerts(project),"events",store.events(project),"retentionDays",properties.getRetentionDays()));
    }
    @GetMapping("/history") public ApiResponse<?> history(@RequestParam(required=false) String projectId,@RequestParam String resourceId,@RequestParam String metric,@RequestParam(defaultValue="120") int limit){
        String project=scope(projectId);
        if(resourceId.length()>128||!java.util.Set.of("CPU_PERCENT","MEMORY_PERCENT","OUTBOX_BACKLOG","TASK_FAILURES","MQ_QUEUE_BACKLOG","MQ_DEAD_LETTERS").contains(metric))throw new IllegalArgumentException("INVALID_MONITOR_SERIES");
        return ApiResponse.ok(store.history(project,resourceId,metric,limit));
    }
}
