package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 创建时空容量桶请求。windowStart/windowEnd 为 epoch 毫秒（UTC），
 * 规范化时向下取整到分钟；桶键为 cellX:cellY:windowStartMin:windowEndMin。
 *
 * @param cellX       空间单元 X 索引
 * @param cellY       空间单元 Y 索引
 * @param windowStart 时间窗起始，epoch 毫秒（UTC）
 * @param windowEnd   时间窗结束，epoch 毫秒（UTC），规范化后须大于起始
 * @param capacity    容量上限（可同时占用航线数），>= 1
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 */
public record CapacityBucketRequest(
        @NotNull Integer cellX,
        @NotNull Integer cellY,
        @NotNull Long windowStart,
        @NotNull Long windowEnd,
        @NotNull @Positive @Max(1000000) Integer capacity,
        @NotBlank @Size(max = 64) String requestId) {
}
