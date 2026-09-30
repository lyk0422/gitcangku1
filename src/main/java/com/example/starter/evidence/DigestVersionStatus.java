package com.example.starter.evidence;

/**
 * 数字摘要封存版本状态。
 * ACTIVE 当前有效版本；SUPERSEDED 已被重新封存产生的新版本取代，旧见证绑定旧版本、不自动转移。
 */
public enum DigestVersionStatus {
    ACTIVE,
    SUPERSEDED
}
