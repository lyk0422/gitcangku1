package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 读数视图（当前值）。
 *
 * @param equipmentId                   所属设备标识
 * @param readingId                     读数标识
 * @param sampledAt                     UTC 采样时刻
 * @param cumulativeMinutes             当前累计工时（分钟）
 * @param loadWeight                    负荷权重档位（NORMAL/HEAVY）
 * @param equivalentCumulativeMinutes   截至该读数的负荷加权等效累计（等效分钟，由当前读数序列推导）
 * @param revisionNo                    当前修订号，初始 1
 * @param anchored                      是否已被某条保养记录锚定（锚定后不可修订）
 * @param equipmentVersion              操作后的设备版本号（仅写操作响应中有意义，查询时为当前版本）
 */
public record ReadingResponse(
        String equipmentId,
        String readingId,
        Instant sampledAt,
        long cumulativeMinutes,
        String loadWeight,
        long equivalentCumulativeMinutes,
        int revisionNo,
        boolean anchored,
        long equipmentVersion) {
}
