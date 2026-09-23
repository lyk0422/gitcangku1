package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 锁定请求：精确根版本、期望的仓库版本号与目标平台。
 *
 * @param targetPlatform 目标平台，必填，格式为 os/arch；只有支持该平台或 ANY 的制品可参与
 */
public record LockRequest(
        @NotBlank String rootName,
        @Positive int rootVersion,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion,
        @NotBlank String targetPlatform) {
}
