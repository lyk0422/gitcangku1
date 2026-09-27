package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 检验预约实体，对应 exam_reservation 表。时段为 UTC 左闭右开区间 [startAt, endAt)。
 * 状态机：RESERVED → IN_PROGRESS → COMPLETED；RESERVED → CANCELLED。
 * 开始后锁定原封签编号与版本（不可改写）；完成时记录结果摘要与新封签校验。
 *
 * @param id                  主键
 * @param reservationKey      预约业务键，全局唯一
 * @param evidenceKey         关联证物业务键
 * @param benchKey            占用的检验台业务键
 * @param examinerId          检验室操作人（创建预约的操作人）
 * @param startAt             预约时段开始时刻（UTC，左闭）
 * @param endAt               预约时段结束时刻（UTC，右开）
 * @param status              预约状态
 * @param originalSealNo      开始检验时锁定的原封签编号；null 表示未开始
 * @param originalSealVersion 开始检验时锁定的原封签版本；null 表示未开始
 * @param startedAt           实际开始检验时刻（UTC）；null 表示未开始
 * @param resultSummary       检验结果摘要；null 表示未完成
 * @param newSealNo           完成时换上的新封签编号；null 表示未完成
 * @param newSealVersion      完成时的新封签版本；null 表示未完成
 * @param newSealNote         完成时新封签校验说明；null 表示未完成或未填写
 * @param completedAt         完成时刻（UTC）；null 表示未完成
 * @param cancelReason        取消原因；null 表示未取消或未填写
 * @param cancelledAt         取消时刻（UTC）；null 表示未取消
 * @param createdAt           预约创建时间（Asia/Shanghai）
 */
public record ExamReservation(
        Long id,
        String reservationKey,
        String evidenceKey,
        String benchKey,
        String examinerId,
        LocalDateTime startAt,
        LocalDateTime endAt,
        ExamReservationStatus status,
        String originalSealNo,
        Integer originalSealVersion,
        LocalDateTime startedAt,
        String resultSummary,
        String newSealNo,
        Integer newSealVersion,
        String newSealNote,
        LocalDateTime completedAt,
        String cancelReason,
        LocalDateTime cancelledAt,
        LocalDateTime createdAt) {
}
