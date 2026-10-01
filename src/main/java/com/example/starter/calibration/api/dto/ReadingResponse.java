package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 批次读数响应。outlierId/outlierStatus 为 null 表示该读数未产生离群记录。
 *
 * @param id                  读数 ID
 * @param readingKey          业务读数键
 * @param batchKey            所属批次键
 * @param measuredAt          测量时刻（UTC）
 * @param value               原始读数（十进制字符串）
 * @param computedValue       未舍入计算值（十进制字符串）
 * @param certificateId       匹配到的证书 ID（证书版本）
 * @param measurementRevision 提交时批次测量修订版本
 * @param excluded            是否已被 REJECT 裁决排除
 * @param outlierId           离群记录 ID；未产生离群时为 null
 * @param outlierStatus       离群记录状态；未产生离群时为 null
 * @param createdAt           提交时间（UTC）
 */
public record ReadingResponse(
        long id,
        String readingKey,
        String batchKey,
        Instant measuredAt,
        String value,
        String computedValue,
        long certificateId,
        int measurementRevision,
        boolean excluded,
        Long outlierId,
        String outlierStatus,
        Instant createdAt) {
}