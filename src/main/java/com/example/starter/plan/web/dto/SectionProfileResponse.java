package com.example.starter.plan.web.dto;

import java.math.BigDecimal;

/**
 * 区段防护档案响应，含当前配置版本。
 */
public record SectionProfileResponse(String sectionId, BigDecimal protectDistanceD, int version) {
}
