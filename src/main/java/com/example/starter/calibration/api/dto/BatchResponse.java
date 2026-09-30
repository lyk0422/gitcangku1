package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 测量批次响应。currentRevision 为当前测量修订版本号；released 表示当前修订是否已放行。
 *
 * @param batchKey        业务批次键
 * @param instrumentId    仪器 ID
 * @param currentRevision 当前测量修订版本号
 * @param status          批次状态：OPEN / RELEASED
 * @param createdBy       批次创建人
 * @param createdAt       创建时间（UTC）
 */
public record BatchResponse(
        String batchKey,
        String instrumentId,
        int currentRevision,
        String status,
        String createdBy,
        Instant createdAt) {
}
