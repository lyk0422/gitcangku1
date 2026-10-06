package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 撤销圆形障碍请求。expectedVersion 为客户端期望的当前障碍版本，
 * 版本不匹配返回 409；成功后障碍版本加一并置为 REVOKED，不再参与包络审查。
 *
 * @param obstacleId      障碍唯一标识
 * @param expectedVersion 期望的当前障碍版本（版本从 1 开始）
 * @param operator        操作者标识（可选），参与幂等身份的结构化比较
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record ObstacleRevokeRequest(
        @NotBlank @Size(max = 64) String obstacleId,
        @NotNull Integer expectedVersion,
        @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}
