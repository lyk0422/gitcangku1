package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 修订圆形障碍请求（圆心或半径变化生成新版本，旧版本不可改）。
 * expectedVersion 不匹配返回 409；成功后档案版本加一并使相关审查失效。
 *
 * @param obstacleId      障碍唯一标识
 * @param operator        操作者标识，参与幂等身份
 * @param expectedVersion 期望的当前档案版本（版本从 1 开始）
 * @param centerX         新圆心 X 坐标（平面米坐标）
 * @param centerY         新圆心 Y 坐标（平面米坐标）
 * @param radius          新障碍半径（非负整数米）
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record ObstacleReviseRequest(
        @NotBlank @Size(max = 64) String obstacleId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull Integer expectedVersion,
        @NotNull @Min(-100000) @Max(100000) Integer centerX,
        @NotNull @Min(-100000) @Max(100000) Integer centerY,
        @NotNull @Min(0) @Max(1000000) Integer radius,
        @NotBlank @Size(max = 64) String requestId) {
}
