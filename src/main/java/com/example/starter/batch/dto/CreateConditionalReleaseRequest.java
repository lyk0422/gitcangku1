package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 创建条件放行请求。conditionKey 全局唯一；conditions 为 1～5 条条件说明；
 * expiresAt 为条件有效期（UTC 时刻，须晚于当前时刻）。
 */
public record CreateConditionalReleaseRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "conditionKey 不能为空") String conditionKey,
        @NotNull(message = "conditions 不能为空")
        @Size(min = 1, max = 5, message = "conditions 必须包含 1～5 条条件说明")
        List<@NotBlank(message = "条件说明不能为空") String> conditions,
        @NotNull(message = "expiresAt 不能为空") Instant expiresAt
) {
}
