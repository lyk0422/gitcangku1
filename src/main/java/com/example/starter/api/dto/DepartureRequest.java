package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 起飞登记请求。仅 ACTIVE 占用可登记起飞；已让渡占用须先重新申请。
 *
 * @param requestId    写操作全局唯一请求标识，用于幂等重放
 * @param occupationId 占用唯一标识
 */
public record DepartureRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String occupationId) {
}
