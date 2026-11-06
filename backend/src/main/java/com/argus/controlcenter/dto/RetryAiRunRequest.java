package com.argus.controlcenter.dto;

import jakarta.validation.constraints.Size;

public class RetryAiRunRequest {
    @Size(max = 500)
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
}
