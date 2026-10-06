package com.example.starter.firmware.domain;

/**
 * 组发布单状态：PREPARING 准备中（可取消）；ACTIVATED 已激活（终态）；
 * ABORTED 准备失败中止（终态）；CANCELLED 已取消（终态）。
 */
public enum GroupReleaseStatus {
    PREPARING,
    ACTIVATED,
    ABORTED,
    CANCELLED
}
