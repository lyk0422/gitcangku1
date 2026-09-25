package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 时空桶引用：网格单元 + 15 分钟 UTC 时间桶。
 *
 * @param cellX       网格单元 X 索引
 * @param cellY       网格单元 Y 索引
 * @param bucketStart 时间桶起始时刻，epoch 毫秒（UTC），15 分钟对齐
 */
public record BucketRefDto(
        @NotNull Integer cellX,
        @NotNull Integer cellY,
        @NotNull Long bucketStart) {
}
