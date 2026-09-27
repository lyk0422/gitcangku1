package com.example.starter.plan.model;

/**
 * 旅客接续保障登记（前车 -> 后车）。同一对计划仅一条；取消后历史行保留。
 * 计划改签后关联仍指向登记时的计划 id，裁决时沿改签链解析到当前生效计划。
 *
 * @param id                 主键
 * @param predecessorPlanId  前车（先到）计划 id
 * @param successorPlanId    后车（后发）计划 id
 * @param transferSectionId  接续换乘站点（区段 ID）
 * @param guaranteeMinutes   保障分钟数 [1,1440]
 * @param predecessorVersion 登记时前车版本（登记证据）
 * @param crossMidnight      是否明确跨零点（后车运营日为前车次日）
 * @param status             登记状态
 * @param createdAt          创建时刻，UTC 毫秒
 * @param cancelledAt        取消时刻，UTC 毫秒；未取消为 null
 */
public record Connection(long id, long predecessorPlanId, long successorPlanId,
                         String transferSectionId, int guaranteeMinutes, int predecessorVersion,
                         boolean crossMidnight, ConnectionStatus status,
                         long createdAt, Long cancelledAt) {
}
