package com.example.starter.plan.model;

/**
 * 区段轴限档案：单轴上限与任意连续 3 轴总重上限。
 *
 * @param sectionId          区段 ID，全局唯一
 * @param version            档案版本，从 1 起，每次修改限值成功加一
 * @param singleAxleLimitKg  单轴轴重上限，单位千克，正整数
 * @param window3LimitKg     任意连续 3 轴总重上限，单位千克，正整数；少于 3 轴不检查
 */
public record SectionAxleLimit(String sectionId, int version, long singleAxleLimitKg,
                               long window3LimitKg) {
}
