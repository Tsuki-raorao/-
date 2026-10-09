package com.argus.controlcenter.identity;

/** 从 SecurityContext 解析操作者；legacy 适配器由当前静态令牌过滤器显式设置。 */
public interface CurrentActorProvider {
    CurrentActor current();
    default CurrentActor requireCurrent() { return current(); }
}
