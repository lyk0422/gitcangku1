package com.example.starter.plan.model;

/**
 * 区段轴重限值档案。
 *
 * @param sectionId         区段 ID，全局唯一
 * @param version           档案版本，从 1 起，每次成功更新加一
 * @param singleAxleLimitKg 单轴上限，单位千克，正数
 * @param window3LimitKg    任意连续 3 轴总重上限，单位千克，正数；少于 3 轴的列车不检查该项
 */
public record SectionLimitProfile(String sectionId, int version, long singleAxleLimitKg,
                                  long window3LimitKg) {
}
