package com.example.starter.web.dto;

import com.example.starter.model.FluidType;
import com.example.starter.model.SlotStatus;
import java.time.Instant;

/**
 * 车辆时间轴条目。
 *
 * @param slotId 时隙 ID
 * @param flightId 航班 ID
 * @param startUtc 时隙开始（UTC，含）
 * @param endUtc 时隙结束（UTC，不含）
 * @param status 时隙状态
 * @param fluidType 完成时使用的液型，未完成时为 null
 * @param completedAt 完成时刻（UTC），未完成时为 null
 * @param deadlineAt 起飞截止时刻（UTC，不含），未完成时为 null
 */
public record VehicleTimelineEntry(
        long slotId,
        long flightId,
        Instant startUtc,
        Instant endUtc,
        SlotStatus status,
        FluidType fluidType,
        Instant completedAt,
        Instant deadlineAt) {
}