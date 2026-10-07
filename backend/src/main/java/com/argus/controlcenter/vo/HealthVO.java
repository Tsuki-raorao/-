package com.argus.controlcenter.vo;

import java.time.Instant;

/** 控制中心健康状态，附带依赖状态和运行模式。 */
public class HealthVO {
    private String status;
    private String service;
    private Instant timestamp;
    private boolean readOnly;
    private boolean databaseReady;

    public HealthVO(String status, String service, Instant timestamp, boolean readOnly, boolean databaseReady) {
        this.status = status;
        this.service = service;
        this.timestamp = timestamp;
        this.readOnly = readOnly;
        this.databaseReady = databaseReady;
    }

    public String getStatus() { return status; }
    public String getService() { return service; }
    public Instant getTimestamp() { return timestamp; }
    public boolean isReadOnly() { return readOnly; }
    public boolean isDatabaseReady() { return databaseReady; }
}
