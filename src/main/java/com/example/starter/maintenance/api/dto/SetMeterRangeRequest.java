package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 登记量程请求：仅可在设备尚无读数时登记不可变正整数量程 M。
 *
 * @param requestId          全局唯一请求标识（幂等键）
 * @param operator           操作者标识（可选，计入幂等身份）
 * @param expectedVersion    设备期望版本号，与当前版本不一致时返回 409
 * @param meterRangeMinutes  工时表量程 M（分钟），正整数，登记后不可变
 */
public record SetMeterRangeRequest(
        @NotBlank String requestId,
        String operator,
        @NotNull Long expectedVersion,
        @NotNull @Positive Long meterRangeMinutes) {
}
