package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 时空桶：空域单元 + 15 分钟 UTC 时间桶。
 *
 * @param cellId      空域单元标识，格式 C<gx>_<gy>
 * @param bucketStart 15 分钟 UTC 时间桶起始，epoch 毫秒（UTC），需对齐 900000 毫秒
 */
public record BucketDto(
        @NotBlank @Size(max = 32) String cellId,
        @NotNull Long bucketStart) {
}
