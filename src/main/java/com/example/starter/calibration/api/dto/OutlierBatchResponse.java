package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 测量批次明细响应。统计为当前实际值；无计入读数时 meanValue/minValue/maxValue 为 null。
 *
 * @param id               批次 ID
 * @param batchKey         业务批次键
 * @param instrumentId     仪器 ID
 * @param certificateId    绑定的证书 ID（证书版本）
 * @param certificateRevoked 绑定证书当前是否已撤销
 * @param targetValue      目标值（十进制字符串）
 * @param allowedDeviation 允许偏差，含端点（十进制字符串）
 * @param revision         当前测量修订版本
 * @param status           状态：OPEN / RELEASED
 * @param readingCount     读数总数（含已排除）
 * @param includedCount    计入统计的读数条数
 * @param excludedCount    已排除的读数条数
 * @param pendingOutliers  待裁决离群记录数（大于 0 时阻断放行）
 * @param meanValue        计入读数均值（十进制字符串）；无计入读数为 null
 * @param minValue         计入读数最小值；无计入读数为 null
 * @param maxValue         计入读数最大值；无计入读数为 null
 * @param createdAt        创建时间（UTC）
 */
public record OutlierBatchResponse(
        long id,
        String batchKey,
        String instrumentId,
        long certificateId,
        boolean certificateRevoked,
        String targetValue,
        String allowedDeviation,
        int revision,
        String status,
        int readingCount,
        int includedCount,
        int excludedCount,
        int pendingOutliers,
        String meanValue,
        String minValue,
        String maxValue,
        Instant createdAt) {
}
