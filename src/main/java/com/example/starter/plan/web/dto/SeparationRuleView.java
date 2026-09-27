package com.example.starter.plan.web.dto;

/**
 * 危险品互斥类别隔离规则视图，按 (categoryA, categoryB) 字典序稳定排序返回。
 */
public record SeparationRuleView(String categoryA, String categoryB, int minSeparation) {
}
