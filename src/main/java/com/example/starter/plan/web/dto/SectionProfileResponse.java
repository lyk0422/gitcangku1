package com.example.starter.plan.web.dto;

import java.math.BigDecimal;

/**
 * 区段防护档案响应。
 */
public record SectionProfileResponse(String sectionId, BigDecimal protectionDistance,
                                     int version) {
}
