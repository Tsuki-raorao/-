package com.argus.controlcenter.identity;

/** 实际请求使用的身份通道；与身份模式配置（LEGACY_TOKEN/OIDC_IDENTITY）分开。 */
public enum AuthMode { LEGACY_TOKEN, OIDC_SESSION, JWT }
