package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 发运请求：由源厂执行，seals 必须恰好覆盖冻结清单中的全部批次（无遗漏、无多余、无重复）。
 */
public record ShipHandoffRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "plant 不能为空") String plant,
        @NotNull(message = "seals 不能为空")
        @Size(min = 1, max = 50, message = "seals 必须包含 1～50 个批次封签")
        List<@Valid SealSpec> seals
) {
}
