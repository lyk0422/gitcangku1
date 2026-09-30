package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 生产线清洁代次更新请求。新代次必须大于批次当前代次（单调递增，禁止回退）；
 * 同 commandKey 同参重放返回首次结果，同键改参返回 409。
 * 已合批子批的固化代次不受父批后续清洁影响。
 */
public record UpdateCleaningGenerationRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "cleaningGeneration 不能为空")
        @PositiveOrZero(message = "cleaningGeneration 必须为非负整数")
        Integer cleaningGeneration
) {
}
