package com.example.starter.evidence;

/**
 * 销毁申请状态。
 * PENDING 待审；HOLD_BLOCKED 冻结阻断（终态，不自动恢复，须重新提交申请）；
 * COMPLETED 已完成销毁（终态，对应证物进入 DESTROYED）。
 */
public enum DestructionStatus {
    PENDING,
    HOLD_BLOCKED,
    COMPLETED
}
