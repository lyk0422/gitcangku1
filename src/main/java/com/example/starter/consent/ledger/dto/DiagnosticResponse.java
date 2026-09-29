package com.example.starter.consent.ledger.dto;

import java.time.Instant;

import com.example.starter.consent.ledger.QuotaStatus;

/**
 * 诊断结果：一次只读检查，汇总授权代次状态、用途额度状态与账本计数；读取本身不改变任何状态。
 *
 * @param subjectKey     主体标识
 * @param purpose        用途
 * @param epoch          授权代次
 * @param grantExists    授权代次是否存在
 * @param grantStatus    授权状态：ACTIVE / REVOKED；授权不存在为 null
 * @param quotaDeclared  额度是否已声明（CLEARED 视为已声明但不可用）
 * @param quotaStatus    额度状态：ACTIVE / CLEARED；未声明为 null
 * @param balance        当前余额（单位：个）；未声明或已清除为 null
 * @param ledgerCount    该主体＋用途＋代次下账本条目数量（单位：条）
 * @param queryCount     其中受限查询核销条目数量（单位：条）
 * @param totalDeducted  历史累计核销对象数（单位：个）
 * @param lastSeq        最近条目序号；无条目为 null
 * @param lastOccurredAt 最近条目裁决时间（UTC，微秒精度）；无条目为 null
 */
public record DiagnosticResponse(
        String subjectKey,
        String purpose,
        int epoch,
        boolean grantExists,
        String grantStatus,
        boolean quotaDeclared,
        QuotaStatus quotaStatus,
        Long balance,
        int ledgerCount,
        int queryCount,
        long totalDeducted,
        Long lastSeq,
        Instant lastOccurredAt) {
}
