package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 锁定请求：精确根版本、目标平台与期望的仓库版本号。
 */
public record LockRequest(
        @NotBlank String rootName,
        @Positive int rootVersion,
        @NotBlank String platform,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion) {

    /** 兼容入口：默认目标平台 jvm。 */
    public LockRequest(String rootName, int rootVersion, Long expectedRepositoryVersion) {
        this(rootName, rootVersion, "jvm", expectedRepositoryVersion);
    }
}
