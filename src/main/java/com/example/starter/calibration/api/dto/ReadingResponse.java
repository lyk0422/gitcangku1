package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 批次读数响应。outlierId/deviation 仅在提交触发离群检测时有值。
 *
 * @param id                 读数 ID
 * @param readingKey         业务读数键
 * @param batchKey           所属批次键
 * @param measuredAt         测量时刻（UTC）
 * @param reading            原始读数（十进制字符串，实际值）
 * @param submittedBy        提交人
 * @param certificateId      匹配到的证书 ID
 * @param certificateVersion 提交时证书版本快照
 * @param batchRevision      提交时批次修订版本快照
 * @param excluded           是否被 REJECT 裁决排除
 * @param outlierId          离群记录 ID；未离群为 null
 * @param deviation          实际偏差 |读数-目标值|（十进制字符串）；未离群为 null
 * @param createdAt          提交时间（UTC）
 */
public record ReadingResponse(
        long id,
        String readingKey,
        String batchKey,
        Instant measuredAt,
        String reading,
        String submittedBy,
        long certificateId,
        int certificateVersion,
        int batchRevision,
        boolean excluded,
        Long outlierId,
        String deviation,
        Instant createdAt) {
}