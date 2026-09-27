package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 限制区段开放清单版本视图。未配置区段 version 为 0、allowedClasses 为 null（表示不限制）。
 */
public record SectionRestrictionView(String sectionId, int version, List<String> allowedClasses,
                                     String operator, Instant createdAt) {
}
