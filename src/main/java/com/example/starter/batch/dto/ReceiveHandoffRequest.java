package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 接收请求：由目标厂执行，必须提交完整 manifest（逐批封签号）与目标厂接收人；
 * items 必须恰好覆盖冻结清单中的全部批次（无遗漏、无多余、无重复），封签号须与发运登记一致。
 */
public record ReceiveHandoffRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "plant 不能为空") String plant,
        @NotBlank(message = "receiver 不能为空") String receiver,
        @NotNull(message = "items 不能为空")
        @Size(min = 1, max = 50, message = "items 必须包含 1～50 个批次封签")
        List<@Valid SealSpec> items
) {
}
