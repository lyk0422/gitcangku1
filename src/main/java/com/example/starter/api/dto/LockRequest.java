package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 锁定请求：精确根版本、期望的仓库版本号与必选目标平台。
 *
 * @param targetPlatform 目标平台，格式 os/arch；根及全部候选须支持该平台或 ANY
 */
public record LockRequest(
        @NotBlank String rootName,
        @Positive int rootVersion,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion,
        @NotBlank String targetPlatform) {

    /** 旧内部调用便捷构造器（测试迁移用，旧数据均为 ANY，兼容任意具体平台）。 */
    public LockRequest(String rootName, int rootVersion, Long expectedRepositoryVersion) {
        this(rootName, rootVersion, expectedRepositoryVersion, "linux/amd64");
    }
}
