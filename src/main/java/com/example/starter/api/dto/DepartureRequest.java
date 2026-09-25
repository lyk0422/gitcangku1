package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 起飞登记请求。仅 APPROVED 且航线/空域版本仍为当前版本的批件可登记。
 *
 * @param clearanceId 批件唯一标识
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 */
public record DepartureRequest(
        @NotBlank @Size(max = 64) String clearanceId,
        @NotBlank @Size(max = 64) String requestId) {
}
