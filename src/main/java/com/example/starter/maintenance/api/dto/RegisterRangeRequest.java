package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 登记设备量程请求：仅可在设备尚无读数时登记一次，量程 M 为正整数且不可变。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param rangeM           工时表量程 M（分钟），正整数；raw 须满足 0 <= raw < M
 * @param operator         操作者标识（可选，参与幂等身份指纹）
 */
public record RegisterRangeRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull @Positive Long rangeM,
        String operator) {
}
