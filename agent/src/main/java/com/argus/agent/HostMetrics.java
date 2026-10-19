package com.argus.agent;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Locale;

/** 只读主机指标：JDK CPU，Linux 内存使用 /proc 的可用量，其他系统使用 JDK。 */
public record HostMetrics(Double cpuPercent, Long memoryBytes, Long memoryTotalBytes) {
    public static HostMetrics read() {
        try {
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double load = os.getCpuLoad();
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
                try { return fromLinuxValues(load, os.getTotalMemorySize(), Files.readString(Path.of("/proc/meminfo"))); }
                catch (IOException | SecurityException unavailable) { return fromSystemValues(load, os.getTotalMemorySize(), -1); }
            }
            return fromSystemValues(load, os.getTotalMemorySize(), os.getFreeMemorySize());
        } catch (RuntimeException unavailable) {
            // 某些精简 JRE 或非标准系统可能没有主机指标，不能影响 Agent 健康接口。
            return new HostMetrics(null, null, null);
        }
    }

    /** Linux MemAvailable includes reclaimable caches; MemFree alone overstates pressure. */
    static HostMetrics fromLinuxValues(double load, long expectedTotal, String meminfo) {
        try {
            Long total=null,available=null;
            for(String line:meminfo.split("\\R")) {
                String[] parts=line.trim().split("\\s+");
                if(parts.length==0 || !(parts[0].equals("MemTotal:")||parts[0].equals("MemAvailable:"))) continue;
                if(parts.length!=3 || !parts[2].equals("kB") || !parts[1].matches("[0-9]+")) throw new IllegalArgumentException();
                long bytes=Math.multiplyExact(Long.parseLong(parts[1]),1024);
                if(parts[0].equals("MemTotal:")) { if(total!=null)throw new IllegalArgumentException();total=bytes; }
                else { if(available!=null)throw new IllegalArgumentException();available=bytes; }
            }
            // A container-scoped JDK total must not be mixed with host /proc availability.
            if(total==null || available==null || total!=expectedTotal) return fromSystemValues(load,expectedTotal,-1);
            return fromSystemValues(load,total,available);
        } catch(RuntimeException invalid) { return fromSystemValues(load,expectedTotal,-1); }
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
