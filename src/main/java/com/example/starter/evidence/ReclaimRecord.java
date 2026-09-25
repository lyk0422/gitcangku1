package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 追缴记录实体，对应 reclaim_record 表。记录只追加、不可变；
 * 追缴时刻固化原应还时刻、逾期分钟数与说明。
 *
 * @param id             主键
 * @param reclaimKey     追缴业务键，全局唯一，兼作幂等键
 * @param loanKey        被追缴的借出业务键
 * @param evidenceKey    关联证物业务键
 * @param borrowerId     被追缴的借出人
 * @param custodianId    提交追缴的保管人
 * @param dueAt          原应还时刻（UTC），追缴时固化
 * @param overdueMinutes 追缴时刻逾期分钟数，追缴时固化
 * @param note           追缴说明
 * @param createdAt      追缴时间（Asia/Shanghai）
 */
public record ReclaimRecord(
        Long id,
        String reclaimKey,
        String loanKey,
        String evidenceKey,
        String borrowerId,
        String custodianId,
        LocalDateTime dueAt,
        long overdueMinutes,
        String note,
        LocalDateTime createdAt) {
}
