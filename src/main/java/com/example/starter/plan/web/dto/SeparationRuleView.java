package com.example.starter.plan.web.dto;

/**
 * 互斥规则视图，类别按字典序排列。
 */
public record SeparationRuleView(String classA, String classB, Integer minSeparation) {
}
