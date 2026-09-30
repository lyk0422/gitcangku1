package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 锁定请求：精确根版本与期望的仓库版本号。
 *
 * @param exceptions 本次锁定引用的高风险豁免 ID 列表；解析结果中每个 HIGH 及以上风险的
 *                   依赖都必须由且仅由一个精确匹配依赖名称与版本的有效豁免覆盖。
 *                   豁免 ID 参与 requestId 幂等摘要：同 requestId 更换豁免集合按异参返回 409。
 */
public record LockRequest(
        @NotBlank String rootName,
        @Positive int rootVersion,
        @NotNull @PositiveOrZero Long expectedRepositoryVersion,
        @Valid @Size(max = 20) List<@Valid ExceptionRef> exceptions) {

    public LockRequest {
        if (exceptions == null) {
            exceptions = List.of();
        } else {
            exceptions = List.copyOf(exceptions);
        }
    }

    /** 不引用任何豁免的锁定请求（兼容未携带豁免清单的调用方）。 */
    public LockRequest(String rootName, int rootVersion, Long expectedRepositoryVersion) {
        this(rootName, rootVersion, expectedRepositoryVersion, List.of());
    }
}
