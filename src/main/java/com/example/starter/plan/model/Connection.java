package com.example.starter.plan.model;

/**
 * 接续关系：两趟已发布计划间的换乘接续。前车改签后随改签链指向新计划；
 * 取消后记录保留，不改写历史。
 *
 * @param id                 主键
 * @param predecessorPlanId  前车（先终到）计划 id
 * @param successorPlanId    后车（后始发）计划 id
 * @param transferStation    换乘站，登记时校验等于前车终到站的终点站与后车始发站的起点站
 * @param guaranteeMinutes   保障分钟数：后车始发不得早于前车终到加该分钟数
 * @param crossMidnight      是否明确跨零点（前后车运营日相邻时必须为 true）
 * @param predecessorVersion 登记时前车计划版本，固化登记所依据的前车版本
 * @param status             接续状态
 * @param createdAt          登记时刻，UTC 毫秒
 * @param cancelledAt        取消时刻，UTC 毫秒；未取消为 null
 */
public record Connection(long id, long predecessorPlanId, long successorPlanId,
                         String transferStation, int guaranteeMinutes, boolean crossMidnight,
                         int predecessorVersion, ConnectionStatus status,
                         long createdAt, Long cancelledAt) {
}
