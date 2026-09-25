package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 设备退役请求。退役后该设备的读数不可再认证（422）。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 */
public record RetireEquipmentRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion) {
}
