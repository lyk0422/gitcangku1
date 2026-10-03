package com.example.starter.calibration;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 绑定查询视图，含固化的校准编号与偏差快照。
 *
 * @param id 绑定主键
 * @param requestId 幂等键
 * @param taskId 任务主键
 * @param taskCode 任务编号
 * @param calibrationId 固化的校准编号
 * @param antennaCode 天线编号
 * @param azimuthDeviationDeg 固化的方位偏差快照，单位度
 * @param elevationDeviationDeg 固化的俯仰偏差快照，单位度
 * @param status 绑定状态
 * @param calibrationInvalidated 所依据校准是否已被作废
 * @param createdAt 绑定创建时刻，UTC
 */
public record BindingView(
        long id,
        String requestId,
        long taskId,
        String taskCode,
        long calibrationId,
        String antennaCode,
        BigDecimal azimuthDeviationDeg,
        BigDecimal elevationDeviationDeg,
        BindingStatus status,
        boolean calibrationInvalidated,
        Instant createdAt) {
}