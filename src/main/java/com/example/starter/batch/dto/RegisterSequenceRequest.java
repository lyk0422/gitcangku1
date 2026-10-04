package com.example.starter.batch.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 生产线加工顺序登记请求。expectedVersion 为期望登记的顺序号（线路乐观版本），
 * 从 1 开始连续递增；与生产线当前 next_seq 不一致时返回 409，禁止插队。
 */
public record RegisterSequenceRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "batchKey 不能为空") String batchKey,
        @NotNull(message = "expectedVersion 不能为空")
        @Min(value = 1, message = "expectedVersion 从 1 开始") Long expectedVersion
) {
}