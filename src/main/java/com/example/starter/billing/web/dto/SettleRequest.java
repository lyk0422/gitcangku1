package com.example.starter.billing.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 结算请求。首次结算与计量更正竞争时，只取同一已提交读数版本，不拼接旧基线与新实际。
 *
 * @param requestId 结算幂等键，同参重放原结果，异参返回 409
 */
public record SettleRequest(
        @NotBlank(message = "requestId不能为空")
        String requestId) {
}
