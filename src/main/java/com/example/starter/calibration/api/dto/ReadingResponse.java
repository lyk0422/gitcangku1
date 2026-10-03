package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 读数响应。outlierId 为 null 表示该读数未触发离群。
 *
 * @param id                 读数 ID
 * @param readingKey         业务读数键
 * @param batchKey           所属批次键
 * @param batchRevision      提交时所属测量修订版本
 * @param measuredAt         测量时刻（UTC）
 * @param value              读数值（十进制字符串）
 * @param submittedBy        读数提交人
 * @param certificateId      匹配到的证书 ID
 * @param certificateVersion 封存的证书版本
 * @param excluded           是否已被 REJECT 裁决排除
 * @param outlierId          触发的离群记录 ID；未触发为 null
 * @param createdAt          提交时间（UTC）
 */
public record ReadingResponse(
        long id,
        String readingKey,
        String batchKey,
        int batchRevision,
        Instant measuredAt,
        String value,
        String submittedBy,
        long certificateId,
        int certificateVersion,
        boolean excluded,
        Long outlierId,
        Instant createdAt) {
}