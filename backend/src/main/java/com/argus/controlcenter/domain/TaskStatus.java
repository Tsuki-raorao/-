package com.argus.controlcenter.domain;

/** 任务在控制中心中的生命周期状态。 */
public enum TaskStatus {
    PENDING, DISPATCHING, DELIVERED, RUNNING, RETRY_WAIT, SUCCEEDED, FAILED, UNKNOWN
}
