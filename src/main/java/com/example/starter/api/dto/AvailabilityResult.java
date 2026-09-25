package com.example.starter.api.dto;

/**
 * 走廊时段可预约探测结果（只读，不建立预约）。
 *
 * @param corridorId 走廊唯一标识
 * @param startTime  探测时段起始（含），ISO-8601 UTC
 * @param endTime    探测时段结束（不含），ISO-8601 UTC
 * @param available  true 表示该时段当前可预约（时间重叠的生效预约数未达容量上限）
 * @param occupancy  与该时段重叠的当前生效预约数
 * @param capacity   当前同时容量上限
 */
public record AvailabilityResult(String corridorId, String startTime, String endTime,
                                 boolean available, int occupancy, int capacity) {
}
