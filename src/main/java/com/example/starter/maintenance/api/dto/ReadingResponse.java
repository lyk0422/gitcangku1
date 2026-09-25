package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 读数视图（当前值）。按设备登记单位展示原始值，并附换算后分钟数。
 *
 * @param equipmentId        所属设备标识
 * @param readingId          读数标识
 * @param sampledAt          UTC 采样时刻
 * @param unit               设备计量单位（存储口径）
 * @param cumulativeValue    当前累计工时（按设备登记单位）
 * @param cumulativeMinutes  当前累计工时（换算后分钟数，判定口径）
 * @param revisionNo         当前修订号，初始 1
 * @param anchored           是否已被某条保养记录锚定（锚定后不可修订）
 * @param equipmentVersion   操作后的设备版本号（仅写操作响应中有意义，查询时为当前版本）
 */
public record ReadingResponse(
        String equipmentId,
        String readingId,
        Instant sampledAt,
        String unit,
        BigDecimal cumulativeValue,
        long cumulativeMinutes,
        int revisionNo,
        boolean anchored,
        long equipmentVersion) {
}
