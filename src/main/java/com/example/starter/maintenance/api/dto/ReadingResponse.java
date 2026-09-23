package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 读数视图（当前值），同时给出表内原始工时与跨表连续虚拟工时。
 *
 * @param equipmentId        所属设备标识
 * @param readingId          读数标识
 * @param meterKey           读数所属工时表标识
 * @param sampledAt          UTC 采样时刻
 * @param rawHours           表内原始工时读数（小时）
 * @param virtualHours       跨表连续虚拟工时（小时）
 * @param virtualMinutes     虚拟工时换算分钟（四舍五入）
 * @param revisionNo         当前修订号，初始 1
 * @param anchored           是否已被某条保养记录锚定（锚定后不可修订）
 * @param equipmentVersion   操作后的设备版本号（仅写操作响应中有意义，查询时为当前版本）
 */
public record ReadingResponse(
        String equipmentId,
        String readingId,
        String meterKey,
        Instant sampledAt,
        BigDecimal rawHours,
        BigDecimal virtualHours,
        long virtualMinutes,
        int revisionNo,
        boolean anchored,
        long equipmentVersion) {
}
