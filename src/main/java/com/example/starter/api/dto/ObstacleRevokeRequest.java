package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 撤销圆形障碍请求。expectedVersion 不匹配返回 409；
 * 成功后档案版本加一并置为 REVOKED，不再参与审查。
 *
 * @param obstacleId      障碍唯一标识
 * @param operator        操作者标识，参与幂等身份
 * @param expectedVersion 期望的当前档案版本（版本从 1 开始）
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record ObstacleRevokeRequest(
        @NotBlank @Size(max = 64) String obstacleId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull Integer expectedVersion,
        @NotBlank @Size(max = 64) String requestId) {
}
