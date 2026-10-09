package com.argus.controlcenter.infra;

import com.argus.controlcenter.domain.Task;

/** 可重建的任务热状态缓存；MySQL 仍是权威事实源。 */
public interface TaskStateCache { void put(Task task); }
