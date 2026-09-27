package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 危险品互斥规则请求项：classA 与 classB 两类车厢在同一编组内至少相隔 minSeparationCars 节车厢。
 */
public record HazardRuleRequest(
        @NotBlank @Pattern(regexp = "[A-Z0-9]{1,32}", message = "危险品类别必须为 1~32 位大写字母或数字")
        String classA,
        @NotBlank @Pattern(regexp = "[A-Z0-9]{1,32}", message = "危险品类别必须为 1~32 位大写字母或数字")
        String classB,
        @NotNull @Positive Integer minSeparationCars) {
}
