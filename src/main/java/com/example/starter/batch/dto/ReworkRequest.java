package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 返工重投请求体。原批次由路径给出，必须为 REJECTED（已完成必做检验且判定不合格、未放行）；
 * reworkKey 全局唯一、同键同参重放返回首次返工结果；reworkBatchKey 为新返工批次业务键。
 */
public record ReworkRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "reworkKey 不能为空") String reworkKey,
        @NotBlank(message = "reworkBatchKey 不能为空") String reworkBatchKey,
        @NotBlank(message = "reworkReason 不能为空")
        @Size(max = 512, message = "reworkReason 长度不能超过 512") String reworkReason
) {
}
