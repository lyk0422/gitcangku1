package com.example.starter.firmware.domain;

/**
 * 投放活动状态：ACTIVE 进行中；ENDED 已结束（终态，结束后禁止入组、回执、恢复与迁移）。
 */
public enum CampaignStatus {
    ACTIVE,
    ENDED
}
