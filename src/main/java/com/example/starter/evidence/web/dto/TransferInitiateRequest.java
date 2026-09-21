package com.example.starter.evidence.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 交接发起请求。toCustodianId 必须与当前保管人不同。
 */
public record TransferInitiateRequest(
        @NotBlank(message = "不能为空") @Size(max = 64) String commandKey,
        @NotBlank(message = "不能为空") @Size(max = 64) String toCustodianId
) {
}
