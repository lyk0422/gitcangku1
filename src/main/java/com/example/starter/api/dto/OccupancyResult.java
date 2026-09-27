package com.example.starter.api.dto;

/**
 * 航线时空占用登记结果。
 *
 * @param occupancyId 占用唯一标识
 * @param routeId     航线标识
 * @param kind        REGULAR / EMERGENCY
 * @param status      登记后状态：ACTIVE
 * @param startMs     时间窗起点（含），epoch 毫秒 UTC
 * @param endMs       时间窗终点（不含），epoch 毫秒 UTC
 * @param altMin      高度下界（含），米
 * @param altMax      高度上界（不含），米
 * @param actualDurationMs 实际占用时长（endMs - startMs），毫秒
 * @param reviewId    本事务内新审查的不可变审查标识
 * @param corridorId  应急占用命中的走廊标识；常规占用为 null
 */
public record OccupancyResult(String occupancyId, String routeId, String kind, String status,
                              long startMs, long endMs, int altMin, int altMax,
                              long actualDurationMs, String reviewId, String corridorId) {
}
