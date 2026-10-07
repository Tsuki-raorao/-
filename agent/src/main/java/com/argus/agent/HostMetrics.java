package com.argus.agent;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;

/** 通过 JDK 管理接口读取主机级只读 CPU 和内存快照。 */
public record HostMetrics(double cpuPercent, long memoryBytes, long memoryTotalBytes) {
    public static HostMetrics read() {
        try {
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double load = os.getCpuLoad();
            double cpu = Double.isFinite(load) && load >= 0 ? Math.min(100, Math.max(0, load * 100)) : 0;
            long total = Math.max(0, os.getTotalMemorySize());
            long free = Math.max(0, os.getFreeMemorySize());
            long used = total >= free ? total - free : 0;
            return new HostMetrics(cpu, used, total);
        } catch (RuntimeException unavailable) {
            // 某些精简 JRE 或非标准系统可能没有主机指标，不能影响 Agent 健康接口。
            return new HostMetrics(0, 0, 0);
        }
    }

    public double memoryPercent() {
        return memoryTotalBytes <= 0 ? 0 : Math.min(100, memoryBytes * 100d / memoryTotalBytes);
    }
}
