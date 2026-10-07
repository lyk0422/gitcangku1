package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 锁定请求：精确根版本、期望的仓库版本号与体积预算。
 *
 * @param budgetBytes 体积预算，单位字节，非负长整型；选中制品（含根）体积总和不得超过
 */
public record LockRequest(
        @NotBlank String rootName,
        @Positive int rootVersion,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion,
        @NotNull @PositiveOrZero Long budgetBytes) {
}
