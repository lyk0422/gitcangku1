package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登记圆形障碍请求。圆心为平面米坐标，半径为非负整数米；登记后档案版本为 1。
 *
 * @param obstacleId 障碍唯一标识
 * @param operator   操作者标识，参与幂等身份
 * @param centerX    圆心 X 坐标（平面米坐标）
 * @param centerY    圆心 Y 坐标（平面米坐标）
 * @param radius     障碍半径（非负整数米）
 * @param requestId  写操作全局唯一请求标识，用于幂等重放
 */
public record ObstacleCreateRequest(
        @NotBlank @Size(max = 64) String obstacleId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Min(-100000) @Max(100000) Integer centerX,
        @NotNull @Min(-100000) @Max(100000) Integer centerY,
        @NotNull @Min(0) @Max(1000000) Integer radius,
        @NotBlank @Size(max = 64) String requestId) {
}
