package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 批次放行响应。封存放行时的修订版本、证书版本与统计实际值。
 *
 * @param batchKey       业务批次键
 * @param revision       放行时的测量修订版本
 * @param status         状态：RELEASED
 * @param certificateId  放行时绑定的证书 ID（证书版本）
 * @param releasedBy     放行人
 * @param includedCount  放行时计入统计的读数条数（实际值）
 * @param excludedCount  放行时已排除的读数条数（实际值）
 * @param meanValue      放行时计入读数均值（十进制字符串）；无计入读数为 null
 * @param releasedAt     放行时间（UTC）
 */
public record BatchReleaseResponse(
        String batchKey,
        int revision,
        String status,
        long certificateId,
        String releasedBy,
        int includedCount,
        int excludedCount,
        String meanValue,
        Instant releasedAt) {
}
