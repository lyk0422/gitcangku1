package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 批次规范换版请求。仅未放行且尚无任何批准的批次可换版；
 * expectedVersion 为当前绑定版本的乐观并发校验，不匹配返回 409。
 */
public record RebindSpecRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "operator 不能为空") String operator,
        @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
        @NotNull(message = "newVersion 不能为空") Integer newVersion
) {
}
