package com.example.starter.evidence.dto;

import com.example.starter.evidence.ExamReservationStatus;

import java.time.LocalDateTime;

/**
 * 检验预约视图。历史时间与封签快照字段在完成/取消后保持原值，后续操作不改写。
 *
 * @param reservationKey     预约业务键
 * @param evidenceKey        关联证物业务键
 * @param benchKey           检验台业务键
 * @param startAt            时段开始时刻（UTC，左闭）
 * @param endAt              时段结束时刻（UTC，右开）
 * @param status             预约状态
 * @param requestedBy        预约操作人（检验室）
 * @param startedAt          实际开始时刻（UTC）；null 未开始
 * @param completedAt        完成时刻（UTC）；null 未完成
 * @param cancelledAt        取消时刻（UTC）；null 未取消
 * @param resultSummary      检验结果摘要；null 未完成
 * @param newSealNo          重封新封签编号；null 未完成
 * @param newSealCheck       新封签校验值；null 未完成
 * @param lockedSealNo       开始时锁定的原封签编号不可变快照；null 未开始
 * @param lockedSealVersion  开始时锁定的原封签版本不可变快照；null 未开始
 * @param createdAt          预约创建时间（Asia/Shanghai）
 * @param updatedAt          最近状态变更时间（Asia/Shanghai）
 */
public record ExamReservationView(
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
