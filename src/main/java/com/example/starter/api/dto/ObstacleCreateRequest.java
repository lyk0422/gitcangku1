package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登记圆形障碍请求。圆心为平面米坐标，半径为非负整数米；档案版本从 1 起。
 *
 * @param obstacleId 障碍唯一标识
 * @param cx         圆心 X 坐标（米）
 * @param cy         圆心 Y 坐标（米）
 * @param radius     障碍半径（非负整数米）
 * @param operator   操作者标识（可选），参与幂等身份的结构化比较
 * @param requestId  写操作全局唯一请求标识，用于幂等重放
 */
public record ObstacleCreateRequest(
        @NotBlank @Size(max = 64) String obstacleId,
        @NotNull @Min(-100000) @Max(100000) Integer cx,
        @NotNull @Min(-100000) @Max(100000) Integer cy,
        @NotNull @Min(0) @Max(100000) Integer radius,
        @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}
