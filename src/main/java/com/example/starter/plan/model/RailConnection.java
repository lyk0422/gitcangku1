package com.example.starter.plan.model;

/**
 * 铁路接续关系：两趟已发布计划的前后车接续。
 * 后车始发必须不早于前车终到加保障分钟数；任一端计划改签后接续指向新计划。
 *
 * @param id                     主键
 * @param connectionKey          接续业务键，全局唯一
 * @param predecessorPlanId      前车计划 id
 * @param successorPlanId        后车计划 id
 * @param guaranteeMinutes       保障分钟数（0~1440）
 * @param crossMidnight          是否明确跨零点（后车始发可落在前车运营日次日）
 * @param status                 接续状态
 * @param regPredecessorVersion  登记时固化的前车计划版本
 * @param regArrivalUtcMillis    登记时固化的前车终到时刻，UTC 毫秒
 * @param regDepartureUtcMillis  登记时固化的后车始发时刻，UTC 毫秒
 */
public record RailConnection(long id, String connectionKey, long predecessorPlanId,
                             long successorPlanId, int guaranteeMinutes, boolean crossMidnight,
                             ConnectionStatus status, int regPredecessorVersion,
                             long regArrivalUtcMillis, long regDepartureUtcMillis) {
}