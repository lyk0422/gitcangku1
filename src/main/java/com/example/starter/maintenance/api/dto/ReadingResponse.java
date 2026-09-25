package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 读数视图（当前值）。存储口径为设备登记单位（cumulativeValue），
 * cumulativeMinutes 为同一 BigDecimal 规则换算的分钟数。
 *
 * @param equipmentId        所属设备标识
 * @param readingId          读数标识
 * @param sampledAt          UTC 采样时刻
 * @param cumulativeValue    当前累计工时（设备登记单位十进制）
 * @param cumulativeMinutes  当前累计工时换算分钟数（判定统一口径）
 * @param revisionNo         当前修订号，初始 1
 * @param anchored           是否已被某条保养记录锚定（锚定后不可修订）
 * @param equipmentVersion   操作后的设备版本号（仅写操作响应中有意义，查询时为当前版本）
 */
public record ReadingResponse(
        String equipmentId,
        String readingId,
        Instant sampledAt,
        BigDecimal cumulativeValue,
        long cumulativeMinutes,
        int revisionNo,
        boolean anchored,
        long equipmentVersion) {
}
