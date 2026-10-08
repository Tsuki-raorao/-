package com.argus.controlcenter.domain;

/** 健康成功但清单失败属于 PARTIAL，不等同于完整同步。 */
public enum SyncStatus { UNKNOWN, OK, PARTIAL, FAILED }
