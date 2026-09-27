package com.example.starter.plan.web.dto;

/**
 * 限制区段配置视图：区段 ID、开放类别清单（字典序）与当前限制区段配置版本。
 */
public record RestrictedSectionView(String sectionId, java.util.List<String> allowedClasses,
                                    Integer sectionVersion) {
}
