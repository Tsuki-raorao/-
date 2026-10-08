package com.argus.controlcenter.domain;

/** 指标可用性独立于数值：真实零值、缺失和旧协议不能混淆。 */
public enum MetricsStatus { AVAILABLE, PARTIAL, UNAVAILABLE, UNKNOWN }
