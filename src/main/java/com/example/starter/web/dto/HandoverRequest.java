package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 批次交接签收请求。
 *
 * @param requestId 幂等请求键
 * @param fromParty 交接交出方
 * @param toParty 交接接收方
 */
public record HandoverRequest(
        @NotBlank(message = "requestId 不能为空") @Size(max = 64) String requestId,
        @NotBlank(message = "fromParty 不能为空") @Size(max = 64) String fromParty,
        @NotBlank(message = "toParty 不能为空") @Size(max = 64) String toParty) {
}