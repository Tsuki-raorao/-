package com.argus.controlcenter.infra;

import com.argus.controlcenter.domain.Task;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis 只保存可丢失的热状态；连接异常时降级，不阻断 MySQL 任务事实写入。 */
@Component
@ConditionalOnProperty(prefix = "argus.redis", name = "enabled", havingValue = "true")
public class RedisTaskStateCache implements TaskStateCache {
    private final StringRedisTemplate redis; private final ObjectMapper json; private final String prefix; private final Duration ttl;
    public RedisTaskStateCache(StringRedisTemplate redis, ObjectMapper json,
                               @Value("${argus.redis.key-prefix:argus:}") String prefix,
                               @Value("${argus.redis.ttl:120s}") Duration ttl) {
        this.redis=redis; this.json=json; this.prefix=prefix; this.ttl=ttl;
    }
    @Override public void put(Task task) {
        try { redis.opsForValue().set(prefix+"task:"+task.getId(),json.writeValueAsString(task),ttl); }
        catch (JsonProcessingException | RuntimeException ignored) { /* 回源 MySQL */ }
    }
}
