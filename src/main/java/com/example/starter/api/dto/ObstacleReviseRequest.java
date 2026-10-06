package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 修订圆形障碍请求。expectedVersion 为客户端期望的当前障碍版本，
 * 版本不匹配返回 409；成功后障碍版本加一并生成新版本，旧版本不可改。
 *
 * @param obstacleId      障碍唯一标识
 * @param expectedVersion 期望的当前障碍版本（版本从 1 开始）
 * @param cx              新圆心 X 坐标（米）
 * @param cy              新圆心 Y 坐标（米）
 * @param radius          新障碍半径（非负整数米）
 * @param operator        操作者标识（可选），参与幂等身份的结构化比较
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record ObstacleReviseRequest(
        @NotBlank @Size(max = 64) String obstacleId,
        @NotNull Integer expectedVersion,
        @NotNull @Min(-100000) @Max(100000) Integer cx,
        @NotNull @Min(-100000) @Max(100000) Integer cy,
        @NotNull @Min(0) @Max(100000) Integer radius,
        @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}
