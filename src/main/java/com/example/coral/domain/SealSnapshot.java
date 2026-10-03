package com.example.coral.domain;

import java.time.Instant;
import java.util.List;

/**
 * 样方封存快照：每个样方仅一个清单版本，固化各状态数量与植株清单（含死亡）。
 *
 * @param id 封存快照主键
 * @param quadratId 被封存样方ID
 * @param aliveCount 封存时存活数量
 * @param deadCount 封存时死亡数量（记录保留）
 * @param pendingCount 封存时待复核数量，封存前必须为0
 * @param plantCodes 封存时植株编号清单（含死亡）
 * @param sealedAt 封存时刻（UTC）
 */
public record SealSnapshot(long id, long quadratId, int aliveCount, int deadCount,
        int pendingCount, List<String> plantCodes, Instant sealedAt) {
}