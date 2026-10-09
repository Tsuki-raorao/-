package com.argus.controlcenter.vo;

import java.util.List;

public record AiReply(String model, String answer, List<EvidenceRef> evidence, boolean readOnly) {
    public record EvidenceRef(String type, String source, String observedAt) { }
}
