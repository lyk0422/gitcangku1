package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 批次放行响应：与放行快照内容一致，后续测量修订不改写该快照。
 *
 * @param batchKey      业务批次键
 * @param revision      放行时测量修订版本
 * @param includedCount 放行时未排除读数数量
 * @param excludedCount 放行时已排除读数数量
 * @param meanValue     放行时未排除读数计算值均值（十进制字符串）；无未排除读数时为 null
 * @param releasedBy    放行人（X-Actor-Id）
 * @param releasedAt    放行时间（UTC）
 */
public record BatchReleaseResponse(
        String batchKey,
        int revision,
        int includedCount,
        int excludedCount,
        String meanValue,
        String releasedBy,
        Instant releasedAt) {
}