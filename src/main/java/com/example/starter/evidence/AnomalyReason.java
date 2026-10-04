package com.example.starter.evidence;

/**
 * 在途签到异常原因。
 * LATE 到达时刻晚于该检查点 UTC 截止时刻；SEAL_MISMATCH 提交封条编号与证物封条不一致。
 */
public enum AnomalyReason {
    LATE,
    SEAL_MISMATCH
}