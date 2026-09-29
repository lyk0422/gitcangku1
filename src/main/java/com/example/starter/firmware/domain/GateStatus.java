package com.example.starter.firmware.domain;

/**
 * 门禁结论状态。当前仅有 READY_FOR_RELEASE：目标版本已完成一次全成功回滚演练，允许创建正式投放发布单。
 * 门禁记录不可变，新的发布版本必须重新演练。
 */
public enum GateStatus {
    READY_FOR_RELEASE
}
