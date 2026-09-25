package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登记跑道请求。
 *
 * @param runwayId       跑道唯一标识
 * @param hourlyCapacity 每 UTC 小时起降容量（起飞与落地各计一次），1~1000
 * @param requestId      写操作全局唯一请求标识，用于幂等重放
 */
public record RunwayCreateRequest(
        @NotBlank @Size(max = 64) String runwayId,
        @Min(1) @Max(1000) int hourlyCapacity,
        @NotBlank @Size(max = 64) String requestId) {
}
