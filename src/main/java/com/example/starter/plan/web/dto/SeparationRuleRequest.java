package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 互斥类别规则项：同计划内两个危险品类别的车厢位置差不得小于最小间隔（车厢数）。
 * 两个类别按字典序规范化，不区分提交顺序；类别相同非法。
 */
public record SeparationRuleRequest(
        @NotBlank String classA,
        @NotBlank String classB,
        @NotNull @Min(1) @Max(999) Integer minSeparation) {
}
