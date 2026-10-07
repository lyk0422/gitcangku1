package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 锁定请求：精确根版本、期望的仓库版本号与体积预算。
 *
 * @param budgetBytes 部署包总字节预算，非负 long；选中制品去重后（同名仅计一次，根计入）
 *                    的精确总大小必须不大于该预算，预算为 0 时只允许全零大小的完整图
 */
public record LockRequest(
        @NotBlank String rootName,
        @Positive int rootVersion,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion,
        @NotNull @PositiveOrZero Long budgetBytes) {
}
