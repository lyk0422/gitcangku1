package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 起飞登记请求。仅 APPROVED（已批准未起飞）航线可登记；
 * 已起飞航线占用容量但不可被抢占。
 *
 * @param routeId   航线唯一标识
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record DepartRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotBlank @Size(max = 64) String requestId) {
}
