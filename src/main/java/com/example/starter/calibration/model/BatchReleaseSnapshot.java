package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次放行快照。放行时封存修订版本、证书版本与统计，后续测量修订不得改写。
 *
 * @param id            快照 ID（自增）
 * @param batchId       批次 ID
 * @param revision      放行时的批次测量修订版本
 * @param certificateId 放行时绑定的证书 ID（证书版本）
 * @param releasedBy    放行人（X-Actor-Id）
 * @param includedCount 放行时计入统计的读数条数
 * @param meanValue     放行时计入读数均值；无计入读数时为 null
 * @param releasedAt    放行时间（UTC）
 */
public record BatchReleaseSnapshot(
        long id,
        long batchId,
        int revision,
        long certificateId,
        String releasedBy,
        int includedCount,
        BigDecimal meanValue,
        Instant releasedAt) {
}
