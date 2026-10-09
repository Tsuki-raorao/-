package com.argus.controlcenter.infra;

import com.argus.controlcenter.domain.Task;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "argus.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopTaskStateCache implements TaskStateCache { @Override public void put(Task task) { } }
