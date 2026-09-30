package com.example.starter.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 不可变计量读数；基线、实际各一条且共享版本号，同版本在一个事务内提交，更正只新增版本。
 *
 * @param id              读数记录 ID
 * @param invitationId    邀约 ID
 * @param userId          用户 ID
 * @param readingType     BASELINE-基线，ACTUAL-实际
 * @param version         版本号，从 1 递增
 * @param energyKwh       电量（kWh，不为负）
 * @param periodStart     该读数时段起点（UTC，含）
 * @param periodEnd       该读数时段终点（UTC，不含）
 * @param correctedReason 更正缘由；首版为 null，校正版本必填
 * @param createdAt       版本提交时间（UTC）
 */
public record MeterReading(
        Long id,
        Long invitationId,
        String userId,
        String readingType,
        int version,
        BigDecimal energyKwh,
        Instant periodStart,
        Instant periodEnd,
        String correctedReason,
        Instant createdAt
) {
    public static final String TYPE_BASELINE = "BASELINE";
    public static final String TYPE_ACTUAL = "ACTUAL";
}
