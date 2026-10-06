package com.example.starter.plan.web.dto;

/**
 * 区段轴重限值档案响应，限值单位千克。
 */
public record SectionLimitProfileResponse(String sectionId, int version, long singleAxleLimitKg,
                                          long window3LimitKg) {
}
