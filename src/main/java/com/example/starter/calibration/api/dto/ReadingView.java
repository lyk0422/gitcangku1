package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 读数明细视图。
 *
 * @param id                 读数 ID
 * @param readingKey         业务读数键
 * @param batchRevision      提交时所属测量修订版本
 * @param measuredAt         测量时刻（UTC）
 * @param value              读数值（十进制字符串）
 * @param submittedBy        读数提交人
 * @param certificateId      匹配到的证书 ID
 * @param certificateVersion 封存的证书版本
 * @param excluded           是否已被 REJECT 裁决排除
 * @param createdAt          提交时间（UTC）
 */
public record ReadingView(
        long id,
        String readingKey,
        int batchRevision,
        Instant measuredAt,
        String value,
        String submittedBy,
        long certificateId,
        int certificateVersion,
        boolean excluded,
        Instant createdAt) {
}