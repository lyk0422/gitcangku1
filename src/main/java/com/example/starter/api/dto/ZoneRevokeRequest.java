package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销禁飞区请求。
 *
 * @param zoneId    禁飞区唯一标识
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record ZoneRevokeRequest(
        @NotBlank @Size(max = 64) String zoneId,
        @NotBlank @Size(max = 64) String requestId) {
}
