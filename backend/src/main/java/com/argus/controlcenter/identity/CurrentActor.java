package com.argus.controlcenter.identity;

/** 由安全上下文建立的操作者快照，禁止从请求 JSON 或自定义 header 创建。 */
public record CurrentActor(String userId, AuthMode authMode, PlatformRole platformRole, boolean legacyOperator) {
    public CurrentActor {
        if (userId == null || userId.isBlank() || authMode == null || platformRole == null)
            throw new IllegalArgumentException("invalid actor");
    }
}
