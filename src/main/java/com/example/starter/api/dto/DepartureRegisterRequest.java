package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 起飞登记请求。仅 ACTIVE 占用可起飞；已让渡占用必须重新审查并显式重新申请。
 *
 * @param occupancyId 占用唯一标识
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 */
public record DepartureRegisterRequest(
        @NotBlank @Size(max = 64) String occupancyId,
        @NotBlank @Size(max = 64) String requestId) {
}
