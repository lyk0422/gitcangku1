package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 追缴记录实体，对应 reclaim_record 表。记录只追加、不可变；
 * 原到期时刻、逾期分钟数与追缴说明在追缴时固化，此后不随借出记录变化。
 *
 * @param id             主键
 * @param reclaimKey     追缴业务键，全局唯一；重复提交按该键幂等返回首次结果
 * @param loanKey        被追缴的借出业务键，全局唯一（同一借出只能被追缴一次）
 * @param evidenceKey    关联证物业务键
 * @param custodianId    提交追缴的保管人（须为借出时的保管人）
 * @param borrowerId     被追缴的借出人
 * @param dueAt          原应还时刻（UTC），追缴时固化
 * @param overdueMinutes 追缴时刻相对原应还时刻的逾期分钟数，追缴时固化
 * @param note           追缴说明
 * @param reclaimedAt    追缴时刻（UTC）
 * @param createdAt      记录创建时间（Asia/Shanghai）
 */
public record ReclaimRecord(
        Long id,
        String reclaimKey,
        String loanKey,
        String evidenceKey,
        String custodianId,
        String borrowerId,
        LocalDateTime dueAt,
        long overdueMinutes,
        String note,
        LocalDateTime reclaimedAt,
        LocalDateTime createdAt) {
}
