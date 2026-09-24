package com.example.starter.plan.web.dto;

/**
 * 夜间计划对联合发布响应：当日（跨零点起始运营日）与次日两张计划发布后的快照，
 * 以及不可变计划对业务键。任一张失败时不返回本响应而是整体 4xx。
 */
public record PairPublishResponse(String nightPairKey, PlanResponse sameDayPlan,
                                  PlanResponse nextDayPlan) {
}
