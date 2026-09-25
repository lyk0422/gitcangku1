package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 容量转配项：把某 ACTIVE 航线版本在源桶的占用移动到目标桶。
 * 每项对应一条航线；同一转配单内航线不得重复。
 *
 * @param routeId         航线标识
 * @param expectedVersion 转配前航线版本（激活时校验，成功后加一）
 * @param sourceBucket    源时空桶（须为该航线当前真实占用）
 * @param targetBucket    目标时空桶（须与源桶在穿越路径上相邻连续）
 */
public record TransferItemDto(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull @Valid BucketRefDto sourceBucket,
        @NotNull @Valid BucketRefDto targetBucket) {
}
