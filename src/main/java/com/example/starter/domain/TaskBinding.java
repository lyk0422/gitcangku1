package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 任务绑定行：固化校准编号与偏差快照，后续新校准或作废不回写历史快照。
 */
public record TaskBinding(
        long id,
        String requestId,
        long taskId,
        long antennaId,
        long calibrationId,
        BigDecimal azimuthDeviation,
        BigDecimal elevationDeviation,
        Instant calibrationValidUntil,
        BindingStatus status,
        boolean voidedMark,
        Instant boundAt,
        Instant updatedAt) {
}