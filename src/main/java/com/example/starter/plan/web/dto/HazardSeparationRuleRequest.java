package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 互斥危险品类别隔离规则请求项；类别对顺序无关，服务端按字典序归一化，(A,B) 与 (B,A) 视为同一条。
 *
 * @param classA  危险品类别 A
 * @param classB  危险品类别 B，不得与 A 相同
 * @param minCars 两车厢之间至少相隔的车厢数（实际相隔 = 位置差 - 1）
 */
public record HazardSeparationRuleRequest(
        @NotBlank String classA,
        @NotBlank String classB,
        @NotNull @Min(0) Integer minCars) {
}
