package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 锁定请求：精确根版本与期望的仓库版本号。
 *
 * @param lockName 锁定图名称（来源策略分组键）；为空表示不纳入来源策略管理的旧锁定图，
 *                 解析与发布均不校验来源策略
 */
public record LockRequest(
        String lockName,
        @NotBlank String rootName,
        @Positive int rootVersion,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion) {

    /** 兼容旧入口：不指定锁定图名称，按旧规则解析（不做来源策略门禁）。 */
    public LockRequest(String rootName, int rootVersion, Long expectedRepositoryVersion) {
        this(null, rootName, rootVersion, expectedRepositoryVersion);
    }
}
