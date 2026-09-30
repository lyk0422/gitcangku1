package com.example.starter.firmware.domain;

/**
 * 冻结审计流水动作：FROZEN 冻结生效；RESUME_REQUESTED 申请恢复；RESUME_APPROVED 审批恢复。
 */
public enum FreezeEventType {
    FROZEN,
    RESUME_REQUESTED,
    RESUME_APPROVED
}
