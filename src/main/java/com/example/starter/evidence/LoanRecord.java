package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 借出记录实体，对应 loan_record 表。记录只追加；归还仅允许 ACTIVE → RETURNED 一次。
 *
 * @param id          主键
 * @param loanKey     借出业务键，全局唯一
 * @param evidenceKey 关联证物业务键
 * @param custodianId 借出期间的保管人（借出不变更保管人）
 * @param borrowerId  实际借用人
 * @param purpose     借用用途
 * @param status      借出状态
 * @param loanedAt    借出时刻（Asia/Shanghai）
 * @param dueAtUtc    UTC 应还时刻（UTC 墙钟存储）
 * @param returnedAt  归还确认时刻；null 表示未归还
 * @param sealIntact  归还封条核验结果；null 表示未归还
 * @param returnNote  归还说明；null 表示未归还
 * @param createdAt   记录创建时间（Asia/Shanghai）
 */
public record LoanRecord(
        Long id,
        String loanKey,
        String evidenceKey,
        String custodianId,
        String borrowerId,
        String purpose,
        LoanStatus status,
        LocalDateTime loanedAt,
        LocalDateTime dueAtUtc,
        LocalDateTime returnedAt,
        Boolean sealIntact,
        String returnNote,
        LocalDateTime createdAt) {
}
