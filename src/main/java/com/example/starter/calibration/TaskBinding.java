package com.example.starter.calibration;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 任务与校准的绑定；绑定固化校准编号与偏差快照。
 *
 * @param id 主键
 * @param requestId 幂等键；仅成功绑定占键
 * @param taskId 被绑定任务主键
 * @param calibrationId 固化的校准编号
 * @param antennaId 天线主键（与任务天线一致，用于重叠占用判定）
 * @param azimuthDeviationDeg 绑定时固化的方位偏差快照，单位度
 * @param elevationDeviationDeg 绑定时固化的俯仰偏差快照，单位度
 * @param status 绑定状态
 * @param calibrationInvalidated 所依据校准是否已被复核作废
 * @param createdAt 绑定创建时刻，UTC
 */
public record TaskBinding(
        Long id,
        String requestId,
        long taskId,
        long calibrationId,
        long antennaId,
        BigDecimal azimuthDeviationDeg,
        BigDecimal elevationDeviationDeg,
        BindingStatus status,
        boolean calibrationInvalidated,
        Instant createdAt) {
}