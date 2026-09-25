package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 管理员配置时空桶容量请求。
 *
 * @param cellX       网格单元 X 索引
 * @param cellY       网格单元 Y 索引
 * @param bucketStart 时间桶起始时刻，epoch 毫秒（UTC），必须 15 分钟对齐
 * @param maxFlights  最大航班占用数（&ge;0）
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 */
public record CapacityConfigRequest(
        @NotNull Integer cellX,
        @NotNull Integer cellY,
        @NotNull Long bucketStart,
        @NotNull @PositiveOrZero Integer maxFlights,
        @NotBlank @Size(max = 64) String requestId) {
}
