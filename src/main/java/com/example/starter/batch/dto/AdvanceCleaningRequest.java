package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 生产线清洁代次推进请求体。代次只增不减：newGeneration 必须大于批次当前清洁代次。
 * 推进只改变批次当前代次，不影响已合批子批在 batch_merge 中固化的历史依据。
 */
public record AdvanceCleaningRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "newGeneration 不能为空")
        @Positive(message = "newGeneration 必须为正整数") Integer newGeneration
) {
}
