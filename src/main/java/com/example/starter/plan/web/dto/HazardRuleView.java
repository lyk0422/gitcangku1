package com.example.starter.plan.web.dto;

/**
 * 危险品互斥规则视图。
 */
public record HazardRuleView(String classA, String classB, int minSeparationCars) {
}
