package com.example.starter.water;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 配水申请实体。状态机：REQUESTED -> APPROVED，任意非取消态可被申请人取消为 CANCELLED，取消不可恢复。
 */
public record WaterAllocation(
        long id,
        String allocationKey,
        long windowId,
        String userId,
        BigDecimal volume,
        String applicant,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    /** 已申请，待批准。 */
    public static final String STATUS_REQUESTED = "REQUESTED";
    /** 已批准，占用水量。 */
    public static final String STATUS_APPROVED = "APPROVED";
    /** 已取消，立即释放占用水量，不可恢复。 */
    public static final String STATUS_CANCELLED = "CANCELLED";
}
