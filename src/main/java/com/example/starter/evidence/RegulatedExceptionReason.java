package com.example.starter.evidence;

/**
 * 受监管交接异常原因。
 * TIMEOUT 到达时刻晚于该点 UTC 截止时刻；SEAL_MISMATCH 签到封条与交接冻结封条不一致。
 */
public enum RegulatedExceptionReason {
    TIMEOUT,
    SEAL_MISMATCH
}