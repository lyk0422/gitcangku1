package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 编组登记信息：调度员在创建日计划时登记的编组属性。
 *
 * @param lengthM            编组长度，单位米，必须为正
 * @param powerSupplyType    编组需求供电制式代码：AC25KV / DC1500V / DC3000V / NONE（无供电需求）
 * @param accessibleRequired 编组是否有无障碍登乘需求
 */
public record ConsistSpec(
        @NotNull @Positive @Max(10000) Integer lengthM,
        @NotBlank String powerSupplyType,
        @NotNull Boolean accessibleRequired) {
}
