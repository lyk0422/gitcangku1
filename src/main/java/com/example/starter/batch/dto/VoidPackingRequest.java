package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 作废封箱请求体；操作人通过 X-Actor-Id 请求头提供，封箱版本由路径 packingId 指定。
 * 仅未放行批次可作废，作废须填写原因。
 */
public record VoidPackingRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "reason 不能为空") String reason
) {
}
