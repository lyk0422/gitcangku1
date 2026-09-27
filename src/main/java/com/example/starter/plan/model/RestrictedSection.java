package com.example.starter.plan.model;

import java.util.List;

/**
 * 危险品限制区段配置：经过该区段的计划编组类别必须落在开放清单内。
 *
 * @param sectionId      区段 ID
 * @param allowedClasses 开放的危险品类别清单（非空），按字典序返回
 */
public record RestrictedSection(String sectionId, List<String> allowedClasses) {
}
