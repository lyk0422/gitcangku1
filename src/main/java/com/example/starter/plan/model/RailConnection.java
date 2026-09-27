package com.example.starter.plan.model;

/**
 * 旅客接续保障关系：前车终到与后车始发之间须保留保障分钟数。
 *
 * @param id                主键
 * @param predecessorPlanId 前车（先到达）计划 id
 * @param successorPlanId   后车（后出发）计划 id
 * @param guaranteeMinutes  保障分钟数（0~720）
 * @param crossMidnight     是否明确跨零点接续
 * @param status            接续状态：ACTIVE 生效 / CANCELLED 已取消
 * @param createdAt         登记时刻，UTC 毫秒
 * @param cancelledAt       取消时刻，UTC 毫秒；null 表示未取消
 */
public record RailConnection(long id, long predecessorPlanId, long successorPlanId,
                             int guaranteeMinutes, boolean crossMidnight, String status,
                             long createdAt, Long cancelledAt) {

    /** 生效中。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 已取消。 */
    public static final String STATUS_CANCELLED = "CANCELLED";
}
