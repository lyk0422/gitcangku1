package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 检验预约实体，对应 exam_reservation 表。记录只追加状态流转，不删除行；
 * 完成与取消后 status 不可再变，历史字段（时间、锁定封签快照、结果摘要）永不被后续操作改写。
 *
 * @param id                  主键
 * @param reservationKey      预约业务键，全局唯一
 * @param evidenceKey         关联证物业务键
 * @param benchKey            占用的检验台业务键
 * @param startAt             预约时段开始时刻（UTC，区间左闭）
 * @param endAt               预约时段结束时刻（UTC，区间右开）
 * @param status              预约状态
 * @param requestedBy        预约操作人（检验室）
 * @param startedAt           实际开始时刻（UTC）；null 表示未开始
 * @param completedAt         完成时刻（UTC）；null 表示未完成
 * @param cancelledAt         取消时刻（UTC）；null 表示未取消
 * @param resultSummary       检验结果摘要；null 表示未完成
 * @param newSealNo           重封新封签编号；null 表示未完成
 * @param newSealCheck        新封签校验值；null 表示未完成
 * @param lockedSealNo        开始时锁定的原封签编号不可变快照；null 表示未开始
 * @param lockedSealVersion   开始时锁定的原封签版本不可变快照；null 表示未开始
 * @param createdAt           预约创建时间（Asia/Shanghai）
 * @param updatedAt           最近状态变更时间（Asia/Shanghai）
 */
public record ExamReservation(
        Long id,
        String reservationKey,
        String evidenceKey,
        String benchKey,
        LocalDateTime startAt,
        LocalDateTime endAt,
        ExamReservationStatus status,
        String requestedBy,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime cancelledAt,
        String resultSummary,
        String newSealNo,
        String newSealCheck,
        String lockedSealNo,
        Integer lockedSealVersion,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
