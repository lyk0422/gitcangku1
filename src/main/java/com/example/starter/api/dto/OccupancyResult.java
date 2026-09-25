package com.example.starter.api.dto;

import java.util.List;

/**
 * 走廊在某一时刻的容量占用查询结果（只读）。
 *
 * @param corridorId   走廊唯一标识
 * @param at           查询时刻，ISO-8601 UTC
 * @param capacity     当前同时容量上限
 * @param occupancy    该时刻生效中的预约数量
 * @param reservations 该时刻生效中的预约列表（按时段起始、预约标识排序）
 */
public record OccupancyResult(String corridorId, String at, int capacity, int occupancy,
                              List<ReservationResult> reservations) {
}
