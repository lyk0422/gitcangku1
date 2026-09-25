package com.example.starter.evidence;

/**
 * 销毁申请状态。
 * PENDING 待审；HOLD_BLOCKED 被有效冻结阻断（终态，冻结结束或解除后不自动批准，须重新提交）；
 * DESTROYED 已完成销毁（终态，保管链与冻结快照不可改写）。
 */
public enum DestructionStatus {
    PENDING,
    HOLD_BLOCKED,
    DESTROYED
}
