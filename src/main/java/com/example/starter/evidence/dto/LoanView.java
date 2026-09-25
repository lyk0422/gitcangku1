package com.example.starter.evidence.dto;

import com.example.starter.evidence.LoanStatus;

import java.time.LocalDateTime;

/**
 * 借出记录视图。status 为落库状态（ACTIVE/RETURNED/RECLAIMED）；
 * effectiveStatus 为实时判定状态：ACTIVE 且当前 UTC 时刻不早于 dueAt 时为 OVERDUE，
 * 不依赖后台任务、不落库。
 *
 * @param loanKey        借出业务键
 * @param evidenceKey    关联证物业务键
 * @param custodianId    借出期间不变的保管人
 * @param borrowerId     实际借用人
 * @param purpose        借出用途
 * @param loanAt         实际借出时刻（UTC）
 * @param dueAt          UTC 应还时刻
 * @param status         落库借出状态
 * @param effectiveStatus 实时判定状态（OVERDUE 逾期 / 与 status 一致）
 * @param overdue        是否已逾期：仅 ACTIVE 且当前 UTC 时刻不早于 dueAt 时为 true
 * @param sealIntact     归还封条核验结果：null 未归还 / true 完好 / false 异常
 * @param returnNote     归还说明；null 表示未归还
 * @param returnedAt     实际归还时刻（UTC）；null 表示未归还
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
        LoanStatus effectiveStatus,
        boolean overdue,
        Boolean sealIntact,
        String returnNote,
        LocalDateTime returnedAt) {
}
