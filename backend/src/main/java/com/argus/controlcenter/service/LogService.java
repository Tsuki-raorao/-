package com.argus.controlcenter.service;
import com.argus.controlcenter.domain.LogEntry;
import com.argus.controlcenter.repository.LogRepository;
import org.springframework.stereotype.Service;
import java.util.*;
/** 日志查询和演示数据初始化服务。 */
@Service public class LogService {
    private final LogRepository repository;
    public LogService(LogRepository repository) { this.repository=repository; }
    public List<LogEntry> find(String instanceId, int limit) { return repository.findByInstanceId(instanceId, limit); }
    public List<LogEntry> find(String instanceId, int limit, String projectId) { return repository.findByInstanceId(instanceId, limit, projectId); }
    public long count() { return repository.count(); }
    public void seed() { repository.save(new LogEntry(UUID.randomUUID().toString(), "mc01", java.time.Instant.now(), "INFO", "Argus demo log: instance is ready")); }
}
