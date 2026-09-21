package com.example.starter.water;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 限供记录实体。同一窗口至多一条 ACTIVE 记录；取消后保留历史，窗口恢复计划水量。
 */
public record WaterRestriction(
        long id,
        long windowId,
        BigDecimal limitVolume,
        String status,
        Instant createdAt,
        Instant cancelledAt) {

    /** 生效中。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 已取消。 */
    public static final String STATUS_CANCELLED = "CANCELLED";
}
