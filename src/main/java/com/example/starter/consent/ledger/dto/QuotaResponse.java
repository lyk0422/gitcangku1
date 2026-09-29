package com.example.starter.consent.ledger.dto;

import java.time.Instant;

import com.example.starter.consent.ledger.QuotaStatus;

/**
 * 额度操作（调整或清除）结果快照。
 *
 * @param requestId     幂等请求标识
 * @param subjectKey    主体标识（操作者）
 * @param purpose       用途
 * @param epoch         额度归属授权代次
 * @param operation     操作类型：QUOTA_ADJUST 调整 / CLEAR 清除
 * @param status        操作后额度状态：ACTIVE / CLEARED
 * @param balanceBefore 操作前余额（单位：个）；首次声明时为 null（null 含义：此前无额度行）
 * @param balanceAfter  操作后余额（单位：个）；CLEARED 时为 null（null 含义：已清除，无可用余额）
 * @param ledgerSeq     对应账本条目序号
 * @param occurredAtUtc 裁决时间（UTC，微秒精度）
 */
public record QuotaResponse(
        String requestId,
        String subjectKey,
        String purpose,
        int epoch,
        String operation,
        QuotaStatus status,
        Long balanceBefore,
        Long balanceAfter,
        long ledgerSeq,
        Instant occurredAtUtc) {
}
