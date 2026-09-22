package com.example.starter.evidence.dto;

import com.example.starter.evidence.LoanStatus;

import java.time.LocalDateTime;

/**
 * 借出记录视图。逾期仅为查询时刻标识（dueAt 早于查询当前时刻且未归还），
 * 不触发自动归还、保管人变更或限制解除。
 *
 * @param loanKey     借出业务键
 * @param evidenceKey 关联证物业务键
 * @param custodianId 借出期间不变的保管人
 * @param borrowerId  实际借用人
 * @param purpose     借出用途
 * @param loanAt      实际借出时刻（UTC）
 * @param dueAt       UTC 应还时刻
 * @param status      借出状态
 * @param overdue     是否已逾期：仅 ACTIVE 且当前 UTC 时刻晚于 dueAt 时为 true
 * @param sealIntact  归还封条核验结果：null 未归还 / true 完好 / false 异常
 * @param returnNote  归还说明；null 表示未归还
 * @param returnedAt  实际归还时刻（UTC）；null 表示未归还
 */
public record LoanView(
        String loanKey,
        String evidenceKey,
        String custodianId,
        String borrowerId,
        String purpose,
        LocalDateTime loanAt,
        LocalDateTime dueAt,
        LoanStatus status,
        boolean overdue,
        Boolean sealIntact,
        String returnNote,
        LocalDateTime returnedAt) {
}
