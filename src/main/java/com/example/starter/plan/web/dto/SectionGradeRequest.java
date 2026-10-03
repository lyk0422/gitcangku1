package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 登记区段线路等级请求，requestKey 为幂等键；重复登记同等级幂等，变更等级覆盖。
 */
public record SectionGradeRequest(
        @NotBlank String requestKey,
        @Min(0) int lineGrade) {
}