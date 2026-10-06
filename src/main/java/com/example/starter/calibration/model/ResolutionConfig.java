package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 仪器分辨率格点配置。每台仪器一行当前配置；版本从 1 起，更新携带 expectedVersion 乐观版本。
 * 仪器只能报告格点上的值：k 为 (x-o)/s 的最近整数（正好半格取偶），量化值为 o+k×s。
 *
 * @param id           配置记录 ID（自增）
 * @param instrumentId 仪器 ID
 * @param step         分辨率步长 s，必须为正，最多 6 位小数
 * @param origin       格点原点 o，可为负，最多 6 位小数
 * @param version      配置版本，从 1 起，每次成功更新 +1
 * @param updatedBy    最近配置人
 * @param createdAt    首次配置时间（UTC）
 * @param updatedAt    最近更新时间（UTC）
 */
public record ResolutionConfig(
        long id,
        String instrumentId,
        BigDecimal step,
        BigDecimal origin,
        int version,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt) {
}
