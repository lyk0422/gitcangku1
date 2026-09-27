package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销应急走廊请求。撤销只影响后续占用；已让渡占用不自动恢复。
 *
 * @param corridorId 走廊唯一标识
 * @param requestId  写操作全局唯一请求标识，用于幂等重放
 */
public record CorridorRevokeRequest(
        @NotBlank @Size(max = 64) String corridorId,
        @NotBlank @Size(max = 64) String requestId) {
}
