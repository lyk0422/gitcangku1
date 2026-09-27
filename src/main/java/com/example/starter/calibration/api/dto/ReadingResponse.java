package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 批次读数响应。outlierId 仅当该读数触发离群记录时非 null。
 *
 * @param id            读数 ID
 * @param batchKey      所属批次键
 * @param batchRevision 提交时的批次修订版本号
 * @param requestId     提交幂等键
 * @param measuredAt    测量时刻（UTC）
 * @param reading       原始读数（十进制字符串）
 * @param submittedBy   读数提交人
 * @param certificateId 匹配到的证书 ID（证书版本）
 * @param computedValue 未舍入校准计算值（十进制字符串）
 * @param outlier       是否离群
 * @param outlierId     离群记录 ID；非离群时为 null
 * @param excluded      是否已被 REJECT 裁决排除
 * @param createdAt     提交时间（UTC）
 */
public record ReadingResponse(
        long id,
        String batchKey,
        int batchRevision,
        String requestId,
        Instant measuredAt,
        String reading,
        String submittedBy,
        long certificateId,
        String computedValue,
        boolean outlier,
        Long outlierId,
        boolean excluded,
        Instant createdAt) {
}
