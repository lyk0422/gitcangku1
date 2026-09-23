package com.example.starter.firmware.domain;

/**
 * 投放队列状态：ACTIVE 投放中；PAUSED 失败率自动暂停（可人工恢复）。
 */
public enum CohortStatus {
    ACTIVE,
    PAUSED
}
