package com.example.starter.evidence.dto;

import com.example.starter.evidence.LoanStatus;

import java.time.LocalDateTime;

/**
 * 借出记录视图。
 *
 * @param loanKey     借出业务键
 * @param evidenceKey 关联证物业务键
 * @param custodianId 借出期间的保管人（借出不变更保管人）
 * @param borrowerId  实际借用人
 * @param purpose     借用用途
 * @param status      借出状态
 * @param loanedAt    借出时刻（Asia/Shanghai）
 * @param dueAtUtc    UTC 应还时刻
 * @param overdue     是否逾期：ACTIVE 且当前 UTC 时刻晚于 dueAtUtc 时为 true，仅查询标识
 * @param returnedAt  归还确认时间；null 表示未归还
 * @param sealIntact  归还封条核验结果；null 表示未归还
 * @param returnNote  归还说明；null 表示未归还
 */
public record LoanView(
        String loanKey,
        String evidenceKey,
        String custodianId,
        String borrowerId,
        String purpose,
        LoanStatus status,
        LocalDateTime loanedAt,
        LocalDateTime dueAtUtc,
        boolean overdue,
        LocalDateTime returnedAt,
        Boolean sealIntact,
        String returnNote) {
}
