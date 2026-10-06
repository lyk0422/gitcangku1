package com.example.starter.firmware.domain;

/**
 * 双分区发布单状态：ACTIVE 投放中；PAUSED 已暂停（禁止新试启动，不撤销已有 TRIAL 的健康确认）。
 */
public enum DualReleaseStatus {
    ACTIVE,
    PAUSED
}
