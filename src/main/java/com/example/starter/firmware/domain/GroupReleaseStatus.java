package com.example.starter.firmware.domain;

/**
 * 组发布状态：PREPARING 准备中（可取消）；ACTIVATED 已激活（终态，不可取消）；
 * ABORTED 任一成员准备失败整组中止（终态）；CANCELLED 已取消（终态）。
 */
public enum GroupReleaseStatus {
    PREPARING,
    ACTIVATED,
    ABORTED,
    CANCELLED
}
