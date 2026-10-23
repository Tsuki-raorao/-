package com.argus.controlcenter.service;

import com.argus.controlcenter.config.AiProperties;
import com.argus.controlcenter.repository.AiAgentRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 处理进程重启或节点故障留下的运行记录。
 *
 * Agent 运行是同步请求，但运行状态持久化在 MySQL；定时恢复器把超时的 RUNNING
 * 记录转换为 RECOVERY_REQUIRED，交给上层重新发起或人工检查，不会静默重复执行模型或运维动作。
 */
@Service
public class AiAgentRecoveryService {
    private final AiAgentRepository repository;
    private final AiProperties properties;

    public AiAgentRecoveryService(AiAgentRepository repository, AiProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "${ARGUS_AI_RECOVERY_INITIAL_DELAY_MS:15000}",
            fixedDelayString = "${ARGUS_AI_RECOVERY_INTERVAL_MS:30000}")
    public void recoverStaleRuns() {
        repository.recoverStaleRuns(Instant.now().minus(properties.getRecoveryStaleAfter()));
    }
}
