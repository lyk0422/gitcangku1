package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 读数视图（当前值）。
 *
 * @param equipmentId        所属设备标识
 * @param readingId          读数标识
 * @param sampledAt          UTC 采样时刻
 * @param cumulativeMinutes  当前累计工时（分钟）
 * @param revisionNo         当前修订号，初始 1
 * @param anchored           是否已被某条保养记录锚定（锚定后不可修订）
 * @param equipmentVersion   操作后的设备版本号（仅写操作响应中有意义，查询时为当前版本）
 */
public record ReadingResponse(
        String equipmentId,
        String readingId,
        Instant sampledAt,
        long cumulativeMinutes,
        int revisionNo,
        boolean anchored,
        long equipmentVersion) {
}
