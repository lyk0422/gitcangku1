package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 读数视图（当前值）。
 *
 * @param equipmentId        所属设备标识
 * @param readingId          读数标识
 * @param sampledAt          UTC 采样时刻
 * @param cumulativeMinutes  当前累计工时（分钟，四舍五入的兼容值）
 * @param cumulativeHours    当前精确累计工时（小时）；漂移修正后为 0.001 小时精度校准值，
 *                           未漂移修正时由分钟值精确换算（6 位小数）
 * @param revisionNo         当前修订号，初始 1
 * @param anchored           是否已被某条保养记录锚定（锚定后不可修订）
 * @param equipmentVersion   操作后的设备版本号（仅写操作响应中有意义，查询时为当前版本）
 */
public record ReadingResponse(
        String equipmentId,
        String readingId,
        Instant sampledAt,
        long cumulativeMinutes,
        BigDecimal cumulativeHours,
        int revisionNo,
        boolean anchored,
        long equipmentVersion) {
}
