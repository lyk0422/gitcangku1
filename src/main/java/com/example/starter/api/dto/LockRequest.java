package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * 锁定请求：精确根版本与期望的仓库版本号。
 *
 * @param exceptions 本次锁定声明使用的风险豁免 ID 列表；解析结果中每个 HIGH 及以上风险
 *                   依赖版本都必须命中其中一个有效且精确匹配的豁免，可多给但不可缺少
 */
public record LockRequest(
        @NotBlank String rootName,
        @Positive int rootVersion,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion,
        List<@NotNull @Positive Long> exceptions) {

    public LockRequest {
        if (exceptions == null) {
            exceptions = List.of();
        } else {
            exceptions = List.copyOf(exceptions);
        }
    }

    /** 不声明豁免的锁定请求（无 HIGH 及以上风险依赖时使用）。 */
    public LockRequest(String rootName, int rootVersion, Long expectedRepositoryVersion) {
        this(rootName, rootVersion, expectedRepositoryVersion, List.of());
    }
}
