package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 锁定请求：精确根版本、目标平台与期望的仓库版本号。
 *
 * @param targetPlatform 目标平台，格式 os/arch；根和全部候选只有支持该平台或 ANY 才可参与
 */
public record LockRequest(
        @NotBlank String rootName,
        @Positive int rootVersion,
        @NotBlank String targetPlatform,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion) {
}
