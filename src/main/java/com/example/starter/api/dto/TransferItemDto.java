package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 容量转配项：把某航线版本在源桶的占用转移到目标桶。项顺序不影响语义。
 *
 * @param routeId         参与航线标识
 * @param expectedVersion 激活时校验的航线版本
 * @param source          源时空桶（该航线版本当前必须占用）
 * @param target          目标时空桶（转配后须与相邻路径连续且不穿越禁飞区）
 */
public record TransferItemDto(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull @Valid BucketDto source,
        @NotNull @Valid BucketDto target) {
}
