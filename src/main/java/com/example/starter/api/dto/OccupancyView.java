package com.example.starter.api.dto;

/**
 * 占用只读视图（查询不修改状态）。时间均为 epoch 毫秒 UTC；未发生的迁移字段为 null。
 *
 * @param occupancyId     占用标识
 * @param routeId         航线标识
 * @param kind            REGULAR / EMERGENCY
 * @param status          ACTIVE / YIELDED / DEPARTED
 * @param yieldable       是否可让渡
 * @param startMs         时间窗起点（含）
 * @param endMs           时间窗终点（不含）
 * @param altMin          高度下界（含），米
 * @param altMax          高度上界（不含），米
 * @param actualDurationMs 实际占用时长（endMs - startMs），毫秒
 * @param reviewId        引用的不可变审查标识
 * @param corridorId      应急占用命中走廊；常规占用为 null
 * @param createdMs       创建时刻
 * @param yieldedMs       让渡时刻；null 未让渡
 * @param yieldCorridorId 导致让渡的走廊标识；null 未让渡
 * @param yieldReason     让渡原因；null 未让渡
 * @param departedMs      起飞时刻；null 未起飞
 */
public record OccupancyView(String occupancyId, String routeId, String kind, String status,
                            boolean yieldable,
                            long startMs, long endMs, int altMin, int altMax, long actualDurationMs,
                            String reviewId, String corridorId, long createdMs,
                            Long yieldedMs, String yieldCorridorId, String yieldReason,
                            Long departedMs) {
}
