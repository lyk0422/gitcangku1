package com.example.starter.repo;

/**
 * 航线时空占用持久化记录。字段除 status 及一次性迁移时间戳外均不可变。
 *
 * @param occupancyId     占用唯一标识
 * @param routeId         航线标识
 * @param kind            REGULAR / EMERGENCY
 * @param status          ACTIVE / YIELDED / DEPARTED
 * @param yieldable       常规占用是否可让渡；应急占用恒为 false
 * @param startMs         时间窗起点（含），epoch 毫秒 UTC
 * @param endMs           时间窗终点（不含），epoch 毫秒 UTC
 * @param altMin          高度下界（含），米
 * @param altMax          高度上界（不含），米
 * @param reviewId        引用的不可变审查标识
 * @param corridorId      应急占用命中的走廊标识；常规占用为 null；让渡后回填原走廊
 * @param requestId       创建占用的请求标识
 * @param createdMs       创建时刻 epoch 毫秒 UTC
 * @param yieldedMs       让渡时刻；null 未让渡
 * @param yieldCorridorId 导致让渡的走廊标识；null 未让渡
 * @param yieldReason     让渡原因；null 未让渡
 * @param departedMs      起飞时刻；null 未起飞
 */
public record OccupancyPo(String occupancyId, String routeId, String kind, String status,
                          boolean yieldable,
                          long startMs, long endMs, int altMin, int altMax,
                          String reviewId, String corridorId, String requestId, long createdMs,
                          Long yieldedMs, String yieldCorridorId, String yieldReason,
                          Long departedMs) {
}
