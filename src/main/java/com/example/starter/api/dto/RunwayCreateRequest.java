package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登记跑道请求。跑道版本从 1 开始，容量用于审查的小时起降架次约束。
 *
 * @param runwayId        跑道唯一标识
 * @param capacityPerHour 每小时起降容量（架次），至少 1
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record RunwayCreateRequest(
        @NotBlank @Size(max = 64) String runwayId,
        @NotNull @Min(1) @Max(100000) Integer capacityPerHour,
        @NotBlank @Size(max = 64) String requestId) {
}
