package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 显式换版请求。仅未放行且尚无任何批准的批次可换版；
 * expectedVersion 必须等于批次当前绑定版本（乐观校验），否则 409。
 * 换版后旧向量与结论保留但不再作为放行依据。
 */
public record RebindSpecRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "operator 不能为空") String operator,
        @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
        @NotNull(message = "newVersion 不能为空") Integer newVersion
) {
}
