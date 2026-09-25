package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 时空桶引用：空域单元 + 15 分钟 UTC 时间桶起点。
 *
 * @param cellId      空域单元标识，格式 gx:gy（1000m×1000m 网格坐标）
 * @param bucketStart 15 分钟 UTC 时间桶起点，epoch 秒，须为 900 的整数倍
 */
public record BucketRefDto(
        @NotBlank @Size(max = 64) String cellId,
        @NotNull Long bucketStart) {
}
