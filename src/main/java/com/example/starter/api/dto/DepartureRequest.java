package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 起飞登记请求：仅 ACTIVE 占用可登记；已让渡占用返回 409，已起飞再次登记返回 409。
 *
 * @param occupancyId 占用唯一标识
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 */
public record DepartureRequest(
        @NotBlank @Size(max = 64) String occupancyId,
        @NotBlank @Size(max = 64) String requestId) {
}
