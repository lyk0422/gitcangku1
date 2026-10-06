package com.example.starter.plan.web.dto;

/**
 * 区段轴限档案响应，含当前版本、单轴上限与连续 3 轴总重上限（单位千克）。
 */
public record SectionAxleLimitResponse(String sectionId, int version, long singleAxleLimitKg,
                                       long window3LimitKg) {
}
