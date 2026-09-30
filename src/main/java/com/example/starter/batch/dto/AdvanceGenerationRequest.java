package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 推进生产线清洁代次请求。清洁代次只能单调向前：newGeneration 必须大于批次当前代次，
 * 代次不表示物理清洁动作次数以外的含义，无时区语义。
 */
public record AdvanceGenerationRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "newGeneration 不能为空")
        @Positive(message = "newGeneration 必须为正整数")
        Integer newGeneration
) {
}
