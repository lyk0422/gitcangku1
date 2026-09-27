package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 检验预约实体，对应 examination_reservation 表。预约行只追加；
 * 状态只允许 SCHEDULED→IN_PROGRESS→COMPLETED 或 SCHEDULED→CANCELLED，条件更新保证不回退。
 * 时段为 UTC 左闭右开 [startAt, endAt)；预约占用检验台容量与证物。
 *
 * @param id                主键
 * @param reservationKey    预约业务键，全局唯一
 * @param evidenceKey       被检验证物业务键
 * @param benchKey          检验台业务键
 * @param examinerId        检验执行人
 * @param requestedBy       创建预约的操作人（证物当前保管人）
 * @param startAt           预约开始时刻（UTC，含）
 * @param endAt             预约结束时刻（UTC，不含）
 * @param status            预约状态
 * @param sealVersionLocked 开始检验时锁定的原封签版本号；NULL 表示尚未开始
 * @param startedAt         实际开始时刻（UTC）；NULL 表示未开始
 * @param completedAt       实际完成时刻（UTC）；NULL 表示未完成
 * @param resultSummary     检验结果摘要；NULL 表示未完成；完成时必须非空
 * @param newSealNo         完成时校验的新封签编号；NULL 表示未完成
 * @param newSealVersion    完成时追加的新封签版本号；NULL 表示未完成
 * @param cancelledAt       取消时刻（UTC）；NULL 表示未取消
 * @param createdAt         记录创建时间（Asia/Shanghai）
 */
public record ExaminationReservation(
        Long id,
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
