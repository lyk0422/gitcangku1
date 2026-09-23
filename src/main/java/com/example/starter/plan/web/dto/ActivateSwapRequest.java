package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 激活容量交换单请求。requestKey 为激活操作的幂等键：
 * 同键同参重放首次响应，同键异参 409，激活失败不占用该键。
 */
public record ActivateSwapRequest(@NotBlank String requestKey) {
}
