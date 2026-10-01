package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 离群记录（表 outlier_record）。检测时封存目标值、允许偏差、读数与偏差，
 * 后续批次配置或测量修订变更不回写本记录；状态由复核裁决单向推进。
 *
 * @param id            离群记录 ID（自增）
 * @param batchId       所属批次 ID
 * @param readingId     离群读数 ID，一条读数最多一条离群记录
 * @param targetValue   封存目标值（检测时批次配置），最多 6 位小数
 * @param tolerance     封存允许偏差（检测时批次配置），最多 6 位小数
 * @param readingValue  封存原始读数，最多 6 位小数
 * @param computedValue 封存未舍入计算值，最多 12 位小数
 * @param deviation     实际偏差 |计算值-目标值|，最多 12 位小数
 * @param status        状态：PENDING 待裁决（含结论相反的待仲裁）/ ACCEPTED 已接受 / REJECTED 已拒绝
 * @param verdictAt     裁决时间（UTC）；未裁决时为 null
 * @param createdAt     检测时间（UTC）
 */
public record OutlierRecord(
        long id,
        long batchId,
        long readingId,
        BigDecimal targetValue,
        BigDecimal tolerance,
        BigDecimal readingValue,
        BigDecimal computedValue,
        BigDecimal deviation,
        OutlierStatus status,
        Instant verdictAt,
        Instant createdAt) {
}