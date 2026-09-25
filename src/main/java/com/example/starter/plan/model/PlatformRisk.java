package com.example.starter.plan.model;

/**
 * 站台长度下调触发的编组超长风险快照。
 *
 * @param id             主键
 * @param planId         受影响计划 id
 * @param platformCode   被下调的站台代码
 * @param previousLength 固化快照：首次下调前站台有效长度（辆），后续再下调不改写
 * @param newLength      本次下调后的站台有效长度（辆）
 * @param consistLength  标记时刻计划编组长度快照（辆）
 * @param markedAt       风险标记时刻，UTC 毫秒
 * @param resolvedAt     风险解除时刻，UTC 毫秒；null 表示风险仍未解除
 */
public record PlatformRisk(long id, long planId, String platformCode, int previousLength,
                           int newLength, int consistLength, long markedAt, Long resolvedAt) {
}
