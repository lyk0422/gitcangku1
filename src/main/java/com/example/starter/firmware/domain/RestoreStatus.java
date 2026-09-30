package com.example.starter.firmware.domain;

/**
 * 恢复申请审批状态：PENDING 待异于冻结人的操作人审批；APPROVED 已批准（冻结解除，终态）。
 */
public enum RestoreStatus {
    PENDING,
    APPROVED
}
