package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销运动间隔许可请求。撤销释放未来占用，历史轨迹保留不可变。
 *
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 * @param operator  操作者标识，参与幂等身份
 * @param permitId  待撤销的许可标识
 */
public record PermitRevokeRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String permitId) {
}