package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 成分修订请求。allergenKey 为幂等键；expectedVersion 为调用方依据的当前成分版本，
 * 与库中版本不一致返回 409；composition 为修订后的完整过敏原集合与隔离级别，
 * 未知过敏原代码或空隔离级别返回 422。
 */
public record ReviseCompositionRequest(
        @NotBlank(message = "allergenKey 不能为空") String allergenKey,
        @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
        @NotNull(message = "composition 不能为空") @Valid CompositionInput composition
) {
}
