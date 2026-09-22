package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 借出记录实体，对应 loan_record 表。记录只追加；归还只允许一次条件更新
 * （status 必须仍为 ACTIVE），归还后历史字段不可覆盖。
 *
 * @param id          主键
 * @param loanKey     借出业务键，全局唯一
 * @param evidenceKey 关联证物业务键
 * @param custodianId 借出时的当前保管人；借出期间不变，归还须由此人确认
 * @param borrowerId  实际借用人
 * @param purpose     借出用途
 * @param loanAt      实际借出时刻（UTC）
 * @param dueAt       UTC 应还时刻（晚于借出时刻且不超过 72 小时）
 * @param status      借出状态
 * @param sealPassed  归还封条核验结果：null 未归还 / true 完好 / false 异常
 * @param returnNote  归还说明；null 表示未归还
 * @param returnedAt  实际归还时刻（UTC）；null 表示未归还
 * @param createdAt   记录创建时间（Asia/Shanghai）
 */
public record LoanRecord(
        Long id,
        String loanKey,
        String evidenceKey,
        String custodianId,
        String borrowerId,
        String purpose,
        LocalDateTime loanAt,
        LocalDateTime dueAt,
        LoanStatus status,
        Boolean sealPassed,
        String returnNote,
        LocalDateTime returnedAt,
        LocalDateTime createdAt) {
}
