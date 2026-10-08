package com.argus.agent;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;

/** 通过 JDK 管理接口读取主机级只读 CPU 和内存快照。 */
public record HostMetrics(Double cpuPercent, Long memoryBytes, Long memoryTotalBytes) {
    public static HostMetrics read() {
        try {
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double load = os.getCpuLoad();
            return fromSystemValues(load, os.getTotalMemorySize(), os.getFreeMemorySize());
        } catch (RuntimeException unavailable) {
            // 某些精简 JRE 或非标准系统可能没有主机指标，不能影响 Agent 健康接口。
            return new HostMetrics(null, null, null);
        }
    }

    /** -1 等不可用值不能冒充真实的 0%；内存总量有效但空闲量异常时保留总量。 */
    static HostMetrics fromSystemValues(double load, long total, long free) {
        Double cpu = Double.isFinite(load) && load >= 0 && load <= 1 ? load * 100 : null;
        Long totalBytes = total > 0 ? total : null;
        Long usedBytes = total > 0 && free >= 0 && free <= total ? total - free : null;
        return new HostMetrics(cpu, usedBytes, totalBytes);
    }

    public Double memoryPercent() {
        return memoryBytes == null || memoryTotalBytes == null || memoryTotalBytes <= 0
                ? null : memoryBytes * 100d / memoryTotalBytes;
    }

    public String metricsStatus() {
        int available = (cpuPercent == null ? 0 : 1) + (memoryBytes == null ? 0 : 1)
                + (memoryTotalBytes == null ? 0 : 1);
        return available == 0 ? "UNAVAILABLE" : available == 3 ? "AVAILABLE" : "PARTIAL";
    }
}
