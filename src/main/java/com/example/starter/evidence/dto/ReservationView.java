package com.example.starter.evidence.dto;

import com.example.starter.evidence.ReservationStatus;

import java.time.LocalDateTime;

/**
 * 检验预约明细视图。时段统一 UTC 左闭右开；null 字段具有明确语义：
 * sealVersionLocked/startedAt 为 null 表示未开始；completedAt/resultSummary/newSealNo/
 * newSealVersion 为 null 表示未完成；cancelledAt 为 null 表示未取消。
 *
 * @param reservationKey    预约业务键
 * @param evidenceKey       被检验证物业务键
 * @param benchKey          检验台业务键
 * @param examinerId        检验执行人
 * @param requestedBy       创建预约的操作人
 * @param startAt           预约开始时刻（UTC，含）
 * @param endAt             预约结束时刻（UTC，不含）
 * @param status            预约状态
 * @param sealVersionLocked 开始时锁定的原封签版本号；null 表示尚未开始
 * @param startedAt         实际开始时刻（UTC）；null 表示未开始
 * @param completedAt       实际完成时刻（UTC）；null 表示未完成
 * @param resultSummary     检验结果摘要；null 表示未完成
 * @param newSealNo         完成时校验的新封签编号；null 表示未完成
 * @param newSealVersion    完成时追加的新封签版本号；null 表示未完成
 * @param cancelledAt       取消时刻（UTC）；null 表示未取消
 * @param createdAt         记录创建时间（Asia/Shanghai）
 */
public record ReservationView(
        String reservationKey,
        String evidenceKey,
        String benchKey,
        String examinerId,
        String requestedBy,
        LocalDateTime startAt,
        LocalDateTime endAt,
        ReservationStatus status,
        Integer sealVersionLocked,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String resultSummary,
        String newSealNo,
        Integer newSealVersion,
        LocalDateTime cancelledAt,
        LocalDateTime createdAt) {
}
